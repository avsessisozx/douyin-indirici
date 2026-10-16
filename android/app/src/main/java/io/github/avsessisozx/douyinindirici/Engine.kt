package io.github.avsessisozx.douyinindirici

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
    var reason: String = "end",   // end | limit | cancel | login_wall | private | empty | stalled
    var private: Boolean = false,
)

/** Douyin sayfalarını açıp veriyi toplayan taraf (Android'de gizli WebView, testlerde sahte). */
interface DouyinWeb {
    var listener: WebListener?
    var cancel: AtomicBoolean
    val userAgent: String
    suspend fun prepare()
    suspend fun resolve(p: Links.Parsed): Links.Parsed
    suspend fun getPost(awemeId: String, url: String, quiet: Boolean = false): JSONObject
    suspend fun collectProfile(secUid: String, limit: Int, onItems: (List<JSONObject>) -> Unit): ProfileResult
    suspend fun loginSession()
    fun cookiesFor(url: String): String?
}

/** Windows sürümündeki engine.py'nin karşılığı. Arayüz snapshot() ile durumu okur. */
class Engine(
    private val webProvider: () -> DouyinWeb?,
    private val sinkFactory: () -> MediaSink,
    private val scope: CoroutineScope,
    private val workers: Int = 3,
) {
    data class Job(val link: String, val mode: String, val settings: SettingsValues)

    private val lock = Any()
    private var cancel = AtomicBoolean(false)

    @Volatile var running = false; private set
    @Volatile var phase = "idle"; private set
    @Volatile var mode = ""; private set
    @Volatile var captcha = false
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
        for (a in listOf(foundPosts, postsDone, postsSkipped, filesTotal, filesDone, filesSkipped, filesFailed)) a.set(0)
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
        if (job.mode != MODE_LOGIN) {
            val p = Links.parse(job.link)
            if (!p.ok) return p.error
            if (job.mode == MODE_SINGLE && p.kind == Links.PROFILE) return msg("engine.single_needs_post")
        }
        synchronized(lock) {
            if (running) return msg("engine.already_running")
            reset()
            cancel = AtomicBoolean(false)
            running = true
            phase = "starting"
            mode = job.mode
        }
        val c = cancel
        scope.launch { runJob(job, c) }
        return null
    }

    fun stop() {
        if (running && !cancel.get()) {
            log("warn", msg("engine.stopping"))
            cancel.set(true)
        }
    }

    private suspend fun runJob(job: Job, cancel: AtomicBoolean) {
        var settings = job.settings
        if (job.mode == MODE_SINGLE) settings = settings.copy(content = Media.CONTENT_ALL, limit = 0)
        var web: DouyinWeb? = null
        try {
            web = webProvider() ?: throw DouyinError(msg("android.no_window"))
            web.cancel = cancel
            web.listener = object : WebListener {
                override fun log(level: String, m: Msg) = this@Engine.log(level, m)
                override fun captcha(active: Boolean) { this@Engine.captcha = active }
            }
            web.prepare()
            if (job.mode == MODE_LOGIN) {
                web.loginSession()
                phase = "done"
                summary = msg("engine.login_done")
                log("ok", summary!!)
                return
            }
            downloadJob(web, job, settings, cancel)
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
            captcha = false
            collecting = false
            current = ""
            running = false
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
        val pool = DownloadPool(this, web, sink, cancel)

        coroutineScope {
            val ws = List(workers) { launch(Dispatchers.IO) { pool.worker(queue) } }
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
                val result = web.collectProfile(secUid, s.limit) { items -> enqueue(queue, items, s) }
                collecting = false
                if (result.authorName.isNotEmpty() && author.isEmpty()) author = result.authorName
                reportProfile(result)
            }
            repeat(workers) { queue.send(null) }
            phase = "downloading"
            ws.joinAll()
        }

        if (cancel.get()) { finishStopped(); return }
        phase = "done"
        summary = msg("summary", "done" to filesDone.get(), "skipped" to filesSkipped.get(),
            "failed" to filesFailed.get(), "unmatched" to postsSkipped.get())
        if (filesDone.get() == 0 && filesSkipped.get() == 0) {
            phase = if (filesFailed.get() > 0 || foundPosts.get() == 0) "error" else "done"
        }
        log(if (phase == "done") "ok" else "error", summary!!)
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
            "end", "stalled" -> {
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

    internal fun setFolder(f: String) { if (folder.isEmpty()) folder = f }
    internal fun setCurrent(name: String) { current = name }
    internal fun setLastLocation(l: String?) { if (l != null) lastLocation = l }

    companion object {
        const val MODE_SINGLE = "single"
        const val MODE_PROFILE = "profile"
        const val MODE_LOGIN = "login"
    }
}

/** İndirme işçileri. Windows sürümündeki DownloadPool'un karşılığı. */
class DownloadPool(
    private val eng: Engine,
    private val web: DouyinWeb,
    private val sink: MediaSink,
    private val cancel: AtomicBoolean,
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
            downloadPost(post)
        }
    }

    private fun downloadPost(post: Media.Post) {
        val folder = Media.authorFolder(post.authorName.ifEmpty { eng.author }, post.authorSecUid)
        eng.setFolder(MediaStoreSink.BASE + folder)
        val results = mutableListOf<String>()
        for (media in post.files) {
            if (cancel.get()) return
            results += downloadFile(folder, media)
        }
        eng.postsDone.incrementAndGet()
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
        })
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
