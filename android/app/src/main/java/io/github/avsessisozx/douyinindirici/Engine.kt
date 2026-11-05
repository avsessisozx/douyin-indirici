package io.github.avsessisozx.douyinindirici

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Kullanıcıya gösterilecek, çevrilebilir açıklamalı hata. */
class DouyinError(val m: Msg) : Exception(m.key)

interface WebListener {
    fun log(level: String, m: Msg)
    fun captcha(active: Boolean)
}

data class ProfileResult(
    var authorName: String = "",
    var reportedCount: Int = 0,
    var collected: Int = 0,
    var reason: String = "end",   // end | limit | cancel | login_wall | private | empty | stalled | up_to_date
    var private: Boolean = false,
    var secUid: String = "",
)

/** Douyin sayfalarını açıp veriyi toplayan taraf (Android'de gizli WebView, testlerde sahte). */
interface DouyinWeb {
    var listener: WebListener?
    var cancel: AtomicBoolean
    /** Duraklatıldığında true döner; tarama döngüsü bu sırada kaydırmayı bekletir. */
    var isPaused: () -> Boolean
    val userAgent: String
    suspend fun prepare()
    suspend fun resolve(p: Links.Parsed): Links.Parsed
    suspend fun getPost(awemeId: String, url: String, quiet: Boolean = false): JSONObject
    /** knownIds: önceden inmiş gönderiler; stopAfterKnown > 0 ise üst üste bu kadar bilinen gönderi görünce tarama biter ("up_to_date"). */
    suspend fun collectProfile(
        secUid: String, limit: Int, onItems: (List<JSONObject>) -> Unit,
        knownIds: Set<String> = emptySet(), stopAfterKnown: Int = 0,
    ): ProfileResult
    suspend fun loginSession()
    fun cookiesFor(url: String): String?
}

/** Windows sürümündeki engine.py'nin karşılığı. Arayüz snapshot() ile durumu okur. */
class Engine(
    private val webProvider: () -> DouyinWeb?,
    private val sinkFactory: () -> MediaSink,
    private val scope: CoroutineScope,
    /** 0 = ayarlardaki "aynı anda inen gönderi" sayısı. */
    private val workers: Int = 0,
    private val historyProvider: () -> HistoryStore? = { null },
    private val alertSound: () -> Unit = {},
) {
    data class Job(val link: String, val mode: String, val settings: SettingsValues)

    private val lock = Any()
    private var cancel = AtomicBoolean(false)
    private val pauseFlag = AtomicBoolean(false)
    @Volatile private var known: Set<String> = emptySet()
    @Volatile private var jobSecUid = ""
    @Volatile private var soundOn = false
    @Volatile internal var history: HistoryStore? = null
    internal class Failed(val n: Int, val name: String)
    internal val failed = ConcurrentHashMap<String, Failed>()      // inemeyen gönderiler: kimlik -> dosya sayısı, ad

    @Volatile var running = false; private set
    @Volatile var phase = "idle"; private set
    @Volatile var mode = ""; private set
    @Volatile var captcha = false; private set
    @Volatile var loginWall = false; private set
    @Volatile var author = ""; private set
    @Volatile var folder = ""; private set
    @Volatile var collecting = false; private set
    @Volatile var summary: Msg? = null; private set
    @Volatile var current = ""; private set
    @Volatile var lastLocation: String? = null; private set
    val foundPosts = AtomicInteger()
    val postsDone = AtomicInteger()
    val postsSkipped = AtomicInteger()
    val postsKnown = AtomicInteger()
    val filesTotal = AtomicInteger()
    val filesDone = AtomicInteger()
    val filesSkipped = AtomicInteger()
    val filesFailed = AtomicInteger()
    val currentBytes = AtomicLong()
    val currentTotal = AtomicLong()

    private val logs = ArrayList<JSONObject>()
    private var logOffset = 0

    private fun reset() {
        phase = "idle"; mode = ""; captcha = false; loginWall = false; author = ""; folder = ""
        collecting = false; summary = null; current = ""; lastLocation = null
        known = emptySet(); jobSecUid = ""
        for (a in listOf(foundPosts, postsDone, postsSkipped, postsKnown, filesTotal, filesDone, filesSkipped, filesFailed)) a.set(0)
        currentBytes.set(0); currentTotal.set(0)
        synchronized(lock) {
            logOffset += logs.size
            logs.clear()
        }
    }

    // ------------------------------------------------------------ kayıt
    fun log(level: String, m: Msg) = addLog(level, m.toJson(), null)
    fun logText(level: String, text: String) = addLog(level, null, text)

    private fun addLog(level: String, m: JSONObject?, text: String?) {
        val entry = JSONObject()
            .put("t", SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date()))
            .put("level", level)
        if (m != null) entry.put("msg", m) else entry.put("text", text ?: "")
        synchronized(lock) {
            logs.add(entry)
            if (logs.size > 2000) {
                val drop = logs.size - 1500
                logs.subList(0, drop).clear()
                logOffset += drop
            }
        }
    }

    fun logKeys(): List<String> = synchronized(lock) {
        logs.mapNotNull { it.optJSONObject("msg")?.optString("key") }
    }

    fun snapshot(since: Int = 0): JSONObject {
        val arr = JSONArray()
        val next: Int
        synchronized(lock) {
            val start = (since - logOffset).coerceAtLeast(0)
            for (i in start until logs.size) arr.put(logs[i])
            next = logOffset + logs.size
        }
        return JSONObject()
            .put("running", running)
            .put("phase", phase)
            .put("mode", mode)
            .put("captcha", captcha)
            .put("login_wall", loginWall)
            .put("author", author)
            .put("folder", folder)
            .put("found_posts", foundPosts.get())
            .put("posts_done", postsDone.get())
            .put("posts_skipped", postsSkipped.get())
            .put("posts_known", postsKnown.get())
            .put("paused", pauseFlag.get())
            .put("failed_posts", failed.size)
            .put("collecting", collecting)
            .put("files_total", filesTotal.get())
            .put("files_done", filesDone.get())
            .put("files_skipped", filesSkipped.get())
            .put("files_failed", filesFailed.get())
            .put("current", current)
            .put("current_bytes", currentBytes.get())
            .put("current_total", currentTotal.get())
            .put("summary", summary?.toJson() ?: JSONObject.NULL)
            .put("logs", arr)
            .put("log_next", next)
    }

    // ------------------------------------------------------------ kontrol
    fun start(job: Job): Msg? {
        synchronized(lock) {
            if (running) return msg("engine.already_running")
        }
        if (job.mode != MODE_LOGIN && job.mode != MODE_RETRY) {
            val p = Links.parse(job.link)
            if (!p.ok) return p.error
            if (job.mode == MODE_SINGLE && p.kind == Links.PROFILE) return msg("engine.single_needs_post")
        }
        val ids: List<String> = if (job.mode == MODE_RETRY) failed.keys.toList() else emptyList()
        if (job.mode == MODE_RETRY && ids.isEmpty()) return msg("engine.retry_none")
        synchronized(lock) {
            if (running) return msg("engine.already_running")
            if (job.mode != MODE_RETRY) failed.clear()
            reset()
            cancel = AtomicBoolean(false)
            pauseFlag.set(false)
            running = true
            phase = "starting"
            mode = job.mode
        }
        val c = cancel
        scope.launch { runJob(job, c, ids) }
        return null
    }

    /** İşi duraklatır: o an inen dosya biter, yenisi başlamaz. */
    fun pause() {
        if (running && pauseFlag.compareAndSet(false, true)) log("warn", msg("engine.paused"))
    }

    fun resume() {
        if (pauseFlag.compareAndSet(true, false)) log("info", msg("engine.resumed"))
    }

    fun isPaused(): Boolean = pauseFlag.get() && !cancel.get()

    internal suspend fun waitPause() {
        while (pauseFlag.get() && !cancel.get()) delay(300)
    }

    internal fun setCaptcha(value: Boolean) {
        if (value && !captcha && soundOn) {
            try { alertSound() } catch (e: Throwable) { /* ses çalınamazsa sorun değil */ }
        }
        captcha = value
    }

    fun stop() {
        if (running && !cancel.get()) {
            log("warn", msg("engine.stopping"))
            cancel.set(true)
        }
    }

    private suspend fun runJob(job: Job, cancel: AtomicBoolean, retryIds: List<String>) {
        var settings = job.settings
        soundOn = settings.sound
        if (job.mode == MODE_SINGLE) settings = settings.copy(content = Media.CONTENT_ALL, limit = 0)
        var web: DouyinWeb? = null
        try {
            web = webProvider() ?: throw DouyinError(msg("android.no_window"))
            web.cancel = cancel
            web.isPaused = { isPaused() }
            web.listener = object : WebListener {
                override fun log(level: String, m: Msg) = this@Engine.log(level, m)
                override fun captcha(active: Boolean) { this@Engine.setCaptcha(active) }
            }
            web.prepare()
            if (job.mode == MODE_LOGIN) {
                web.loginSession()
                phase = "done"
                summary = msg("engine.login_done")
                log("ok", summary!!)
                return
            }
            history = openHistory(settings)
            if (job.mode == MODE_RETRY) retryJob(web, retryIds, settings, cancel)
            else downloadJob(web, job, settings, cancel)
        } catch (e: DouyinError) {
            if (cancel.get()) finishStopped() else {
                phase = "error"; summary = e.m; log("error", e.m)
            }
        } catch (e: CancelledException) {
            finishStopped()
        } catch (e: CancellationException) {
            finishStopped()
        } catch (e: Throwable) {
            if (cancel.get()) finishStopped() else {
                log("error", msg("engine.unexpected", "error" to (e.message ?: e.javaClass.simpleName)))
                val sw = StringWriter(); e.printStackTrace(PrintWriter(sw)); logText("debug", sw.toString())
                phase = "error"
                summary = msg("engine.unexpected_summary")
            }
        } finally {
            web?.listener = null
            history = null
            pauseFlag.set(false)
            captcha = false
            collecting = false
            current = ""
            running = false
        }
    }

    private fun openHistory(s: SettingsValues): HistoryStore? {
        if (!s.history) return null
        return try {
            historyProvider()
        } catch (e: Throwable) {      // geçmiş dosyası açılamazsa geçmişsiz devam et
            log("warn", msg("engine.history_failed", "error" to (e.message ?: e.javaClass.simpleName)))
            null
        }
    }

    private fun finishStopped() {
        phase = "stopped"
        summary = msg("engine.stopped", "n" to filesDone.get())
        log("warn", summary!!)
    }

    private suspend fun downloadJob(web: DouyinWeb, job: Job, s: SettingsValues, cancel: AtomicBoolean) {
        val parsed = web.resolve(Links.parse(job.link))
        val sink = sinkFactory()
        val queue = Channel<Media.Post?>(Channel.UNLIMITED)
        val pool = DownloadPool(this, web, sink, cancel, s)
        val n = if (workers > 0) workers else s.parallel

        coroutineScope {
            val ws = List(n) { launch(Dispatchers.IO) { pool.worker(queue) } }
            if (job.mode == MODE_SINGLE) {
                if (parsed.kind == Links.PROFILE) throw DouyinError(msg("engine.short_to_profile"))
                phase = "collecting"
                val item = web.getPost(parsed.awemeId, parsed.url)
                enqueue(queue, listOf(item), s)
            } else {
                var secUid = parsed.secUid
                if (parsed.kind == Links.POST && secUid.isEmpty()) {
                    phase = "collecting"
                    log("info", msg("engine.finding_owner"))
                    val item = web.getPost(parsed.awemeId, parsed.url)
                    val a = item.optJSONObject("author")
                    secUid = (Media.get(a, "sec_uid", "secUid") as? String ?: "").trim()
                    if (secUid.isEmpty()) throw DouyinError(msg("engine.owner_not_found"))
                    val name = (Media.get(a, "nickname") as? String ?: "").trim()
                    if (name.isNotEmpty()) log("info", msg("engine.profile_name", "name" to name))
                }
                phase = "collecting"
                collecting = true
                known = emptySet()
                jobSecUid = secUid
                var stopKnown = 0
                val h = history
                if (h != null && secUid.isNotEmpty()) {
                    known = h.known(secUid)
                    if (known.isNotEmpty()) {
                        log("info", msg("engine.history_known", "n" to known.size))
                        if (h.wasScanned(secUid)) stopKnown = KNOWN_STOP
                    }
                }
                val result = web.collectProfile(secUid, s.limit, { items -> enqueue(queue, items, s) }, known, stopKnown)
                collecting = false
                if (result.authorName.isNotEmpty() && author.isEmpty()) author = result.authorName
                reportProfile(result)
            }
            repeat(n) { queue.send(null) }
            phase = "downloading"
            ws.joinAll()
        }

        if (cancel.get()) { finishStopped(); return }
        if (failed.isNotEmpty()) {
            log("info", msg("engine.retrying", "n" to failed.size))
            retryPass(web, sink, s, cancel, null)
            if (cancel.get()) { finishStopped(); return }
        }
        if (postsKnown.get() > 0) log("info", msg("engine.history_skipped", "n" to postsKnown.get()))
        phase = "done"
        summary = msg("summary", "done" to filesDone.get(), "skipped" to filesSkipped.get(),
            "failed" to filesFailed.get(), "unmatched" to postsSkipped.get())
        if (filesDone.get() == 0 && filesSkipped.get() == 0) {
            phase = if (filesFailed.get() > 0 || foundPosts.get() == 0) "error" else "done"
        }
        log(if (phase == "done") "ok" else "error", summary!!)
    }

    /** "İnemeyenleri tekrar dene": önceki işte inemeyen gönderileri baştan alıp indirir. */
    private suspend fun retryJob(web: DouyinWeb, ids: List<String>, s: SettingsValues, cancel: AtomicBoolean) {
        phase = "downloading"
        log("info", msg("engine.retrying", "n" to ids.size))
        retryPass(web, sinkFactory(), s, cancel, ids)
        if (cancel.get()) { finishStopped(); return }
        phase = "done"
        summary = msg("summary", "done" to filesDone.get(), "skipped" to filesSkipped.get(),
            "failed" to filesFailed.get(), "unmatched" to postsSkipped.get())
        log(if (failed.isEmpty()) "ok" else "error", summary!!)
    }

    /** İnemeyen gönderilerin sayfasını yeniden açıp (adresler eskimiş olabilir) bir kez daha indirir. */
    private suspend fun retryPass(web: DouyinWeb, sink: MediaSink, s: SettingsValues, cancel: AtomicBoolean, only: List<String>?) {
        val ids = only ?: failed.keys.toList()
        val queue = Channel<Media.Post?>(Channel.UNLIMITED)
        val pool = DownloadPool(this, web, sink, cancel, s)
        val n = if (workers > 0) workers else s.parallel
        coroutineScope {
            val ws = List(n) { launch(Dispatchers.IO) { pool.worker(queue) } }
            try {
                for (id in ids) {
                    if (cancel.get()) break
                    waitPause()
                    val item = try {
                        web.getPost(id, "https://www.douyin.com/video/$id", true)
                    } catch (e: DouyinError) {
                        log("warn", msg("engine.download_failed", "name" to id, "error" to e.m))
                        continue
                    }
                    val post = Media.buildPost(item, Media.CONTENT_ALL, s.quality, s.compat)
                    if (post.awemeId.isEmpty() || post.skippedReason != null) continue
                    val prev = failed.remove(id)
                    if (prev != null) filesFailed.addAndGet(-prev.n)
                    else filesTotal.addAndGet(post.files.size)
                    queue.trySend(post)
                }
            } finally {
                repeat(n) { queue.send(null) }
            }
            ws.joinAll()
        }
    }

    private fun reportProfile(r: ProfileResult) {
        when (r.reason) {
            "private" -> throw DouyinError(msg("engine.private"))
            "empty" -> throw DouyinError(msg("engine.empty"))
        }
        if (r.reason == "stalled" && r.collected == 0) throw DouyinError(msg("engine.no_list"))
        when (r.reason) {
            "login_wall" -> {
                loginWall = true
                if (r.reportedCount > 0) log("warn", msg("engine.login_wall_total", "n" to r.collected, "total" to r.reportedCount))
                else log("warn", msg("engine.login_wall", "n" to r.collected))
            }
            "limit" -> log("info", msg("engine.limit_reached", "n" to r.collected))
            "up_to_date" -> log("ok", msg("engine.up_to_date", "n" to r.collected))
            "end", "stalled" -> {
                if (r.reason == "end" && r.secUid.isNotEmpty()) history?.setScanned(r.secUid, r.authorName.ifEmpty { author })
                if (r.reportedCount > 0) log("ok", msg("engine.profile_scanned_total", "n" to r.collected, "total" to r.reportedCount))
                else log("ok", msg("engine.profile_scanned", "n" to r.collected))
            }
        }
    }

    private fun enqueue(queue: Channel<Media.Post?>, items: List<JSONObject>, s: SettingsValues) {
        for (item in items) {
            val post = Media.buildPost(item, s.content, s.quality, s.compat)
            if (post.awemeId.isEmpty()) continue
            foundPosts.incrementAndGet()
            if (author.isEmpty() && post.authorName.isNotEmpty()) author = post.authorName
            if (post.awemeId in known) {
                postsKnown.incrementAndGet()
                continue
            }
            val reason = post.skippedReason
            if (reason != null) {
                postsSkipped.incrementAndGet()
                log("skip", msg("engine.skipped_post", "reason" to reason, "name" to post.stem))
                continue
            }
            filesTotal.addAndGet(post.files.size)
            queue.trySend(post)
        }
    }

    internal fun currentSecUid(): String = jobSecUid
    internal fun setFolder(f: String) { if (folder.isEmpty()) folder = f }
    internal fun setCurrent(name: String) { current = name }
    internal fun setLastLocation(l: String?) { if (l != null) lastLocation = l }

    companion object {
        const val MODE_SINGLE = "single"
        const val MODE_PROFILE = "profile"
        const val MODE_LOGIN = "login"
        const val MODE_RETRY = "retry"
        const val KNOWN_STOP = 10      // üst üste bu kadar bilinen gönderi görünce "yenilerin sonu" sayılır
    }
}

/** İndirme işçileri. Windows sürümündeki DownloadPool'un karşılığı. */
class DownloadPool(
    private val eng: Engine,
    private val web: DouyinWeb,
    private val sink: MediaSink,
    private val cancel: AtomicBoolean,
    private val settings: SettingsValues,
) {
    private fun headers(url: String): Map<String, String> {
        val h = linkedMapOf(
            "User-Agent" to web.userAgent,
            "Referer" to "https://www.douyin.com/",
            "Accept" to "*/*",
        )
        web.cookiesFor(url)?.takeIf { it.isNotBlank() }?.let { h["Cookie"] = it }
        return h
    }

    suspend fun worker(queue: Channel<Media.Post?>) {
        for (post in queue) {
            if (post == null) return
            if (cancel.get()) continue
            eng.waitPause()
            downloadPost(post)
        }
    }

    private suspend fun downloadPost(post: Media.Post) {
        val folder = Media.authorFolder(post.authorName.ifEmpty { eng.author }, post.authorSecUid)
        eng.setFolder(MediaStoreSink.BASE + folder)
        val results = mutableListOf<String>()
        for (media in post.files) {
            if (cancel.get()) return
            eng.waitPause()
            results += downloadFile(folder, media)
        }
        eng.postsDone.incrementAndGet()
        val failedN = results.count { it == "failed" }
        if (failedN > 0) {
            eng.failed[post.awemeId] = Engine.Failed(failedN, post.stem)
        } else if (results.isNotEmpty()) {
            eng.failed.remove(post.awemeId)
            try { eng.history?.mark(eng.currentSecUid().ifEmpty { post.authorSecUid }, post.awemeId, post.authorName) } catch (e: Throwable) { }
        }
        if (results.isNotEmpty() && results.none { it == "failed" }) {
            val key = when {
                results.any { it == "downloaded" } -> if (post.isGallery) "engine.downloaded_gallery" else "engine.downloaded_video"
                else -> "engine.already_had"
            }
            eng.log("ok", msg(key, "name" to post.stem))
        }
    }

    /** http adresleri için önce https dener (IP adreslerine dokunmaz). */
    private fun withHttps(urls: List<String>): List<String> {
        val out = LinkedHashSet<String>()
        for (u in urls) {
            if (u.startsWith("http://")) {
                val host = u.removePrefix("http://").substringBefore('/').substringBefore(':')
                if (!host.all { it.isDigit() || it == '.' } && host != "localhost") out += "https://" + u.removePrefix("http://")
            }
            out += u
        }
        return out.toList()
    }

    private fun downloadFile(folder: String, media: Media.MediaFile): String {
        val rel = "$folder/${media.relStem}"
        if (sink.exists(rel, media.ext)) {
            eng.filesSkipped.incrementAndGet()
            return "skipped"
        }
        eng.setCurrent(rel.substringAfter('/').replace('/', '_'))
        eng.currentBytes.set(0)
        eng.currentTotal.set(media.expectedSize)
        val fallbackExt = if (media.kind == "video" || media.kind == "live") ".mp4" else ".jpg"
        val urls = withHttps(media.urls)
        val result = Fetcher.fetch(urls, rel, media.ext, fallbackExt, headers(urls.first()), sink, cancel, { done, total ->
            eng.currentBytes.set(done)
            eng.currentTotal.set(if (total > 0) total else media.expectedSize)
        }, chunks = settings.chunks)
        if (result.ok) {
            eng.filesDone.incrementAndGet()
            eng.setLastLocation(result.location)
            return "downloaded"
        }
        if (cancel.get()) return "failed"
        eng.filesFailed.incrementAndGet()
        eng.log("error", msg("engine.download_failed", "name" to rel.substringAfter('/'), "error" to (result.error ?: "")))
        return "failed"
    }
}
