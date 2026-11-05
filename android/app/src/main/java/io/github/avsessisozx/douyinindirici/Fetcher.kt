package io.github.avsessisozx.douyinindirici

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** İndirilen dosyanın yazılacağı yer. Android'de galeri (MediaStore), testlerde düz klasör. */
interface MediaSink {
    /** relStem: "Yazar/2024-06-01_baslik_123" ya da "Yazar/2024-.._123/01" (uzantısız). */
    fun exists(relStem: String, ext: String): Boolean
    fun begin(relStem: String, ext: String, mime: String): PendingFile
}

interface PendingFile {
    val stream: OutputStream
    fun commit(): String?   // kaydedilen dosyanın adresi (galeride açmak için)
    fun abort()
}

class CancelledException : Exception("cancelled")

/** Python fetcher.py'nin karşılığı: adresleri sırayla dener, ilk başarılı indirmeyi kaydeder. */
object Fetcher {
    data class Result(val ok: Boolean, val location: String? = null, val size: Long = 0, val error: Msg? = null, val status: Int = 0)

    private const val CHUNK = 256 * 1024
    private const val TIMEOUT_MS = 30_000
    private const val MIN_CHUNKED = 1024L * 1024        // bundan küçük dosyalar tek bağlantıyla inmeye değer
    private const val MIN_SEGMENT = 512L * 1024         // bir parça en az bu kadar olsun

    private val CONTENT_EXT = mapOf(
        "image/jpeg" to ".jpg", "image/jpg" to ".jpg", "image/png" to ".png", "image/webp" to ".webp",
        "image/heic" to ".heic", "image/heif" to ".heic", "image/avif" to ".avif", "image/gif" to ".gif",
        "video/mp4" to ".mp4",
    )

    fun extFromContentType(contentType: String?, fallback: String): String =
        CONTENT_EXT[(contentType ?: "").substringBefore(';').trim().lowercase()] ?: fallback

    fun mimeForExt(ext: String): String = when (ext) {
        ".mp4" -> "video/mp4"
        ".png" -> "image/png"
        ".webp" -> "image/webp"
        ".heic" -> "image/heic"
        ".avif" -> "image/avif"
        ".gif" -> "image/gif"
        else -> "image/jpeg"
    }

    fun fetch(
        urls: List<String>,
        relStem: String,
        ext: String,
        fallbackExt: String,
        headers: Map<String, String>,
        sink: MediaSink,
        cancel: AtomicBoolean,
        onProgress: ((Long, Long) -> Unit)? = null,
        attemptsPerUrl: Int = 2,
        chunks: Int = 1,
    ): Result {
        var last = Result(false, error = msg("fetch.no_url"))
        for (url in urls) {
            for (attempt in 0 until attemptsPerUrl) {
                if (cancel.get()) throw CancelledException()
                var r: Result? = null
                if (chunks > 1) r = fetchChunked(url, relStem, ext, fallbackExt, headers, sink, cancel, onProgress, chunks)
                if (r == null) r = fetchOne(url, relStem, ext, fallbackExt, headers, sink, cancel, onProgress)
                if (r.ok) return r
                last = r
                if (r.status == 403 || r.status == 404 || r.status == 410) break
            }
        }
        return last
    }

    private class Probe(val ok: Boolean, val total: Long = 0, val ranged: Boolean = false, val contentType: String = "")

    /** 1 baytlık parçalı istek: sunucu parçalı indirmeyi destekliyor mu, dosya ne kadar büyük. */
    private fun probe(url: String, headers: Map<String, String>): Probe {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 15_000
                instanceFollowRedirects = true
                for ((k, v) in headers) setRequestProperty(k, v)
                setRequestProperty("Range", "bytes=0-0")
            }
            val status = conn.responseCode
            val type = conn.contentType ?: ""
            val low = type.lowercase()
            if (low.startsWith("text/html") || low.startsWith("application/json") || low.startsWith("text/plain")) return Probe(false)
            if (status == 206) {
                val m = Regex("/(\\d+)\\s*$").find(conn.getHeaderField("Content-Range") ?: "")
                if (m != null) return Probe(true, m.groupValues[1].toLong(), true, type)
            }
            if (status in 200..299) return Probe(true, conn.contentLengthLong.coerceAtLeast(0), false, type)
            return Probe(false)
        } catch (e: Exception) {
            return Probe(false)
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Dosyayı parçalara bölüp aynı anda indirir (geçici dosyaya), sonra galeriye sırayla yazar.
     * Uygun değilse ya da bir parça inmezse null döner; çağıran tek bağlantıyı dener.
     */
    private fun fetchChunked(
        url: String, relStem: String, ext: String, fallbackExt: String, headers: Map<String, String>,
        sink: MediaSink, cancel: AtomicBoolean, onProgress: ((Long, Long) -> Unit)?, chunks: Int,
    ): Result? {
        val pr = probe(url, headers)
        if (!(pr.ok && pr.ranged && pr.total >= MIN_CHUNKED)) return null
        val n = minOf(chunks.toLong(), pr.total / MIN_SEGMENT).toInt()
        if (n < 2) return null
        val total = pr.total
        val size = (total + n - 1) / n
        val spans = (0 until n).map { it * size to minOf(total, (it + 1) * size) - 1 }.filter { it.first < total }
        val tmp = try { File.createTempFile("douyin", ".part") } catch (e: Exception) { return null }
        val failed = AtomicBoolean(false)
        val done = AtomicLong()
        val pool = Executors.newFixedThreadPool(spans.size)
        var pending: PendingFile? = null
        try {
            RandomAccessFile(tmp, "rw").use { it.setLength(total) }
            val futures: List<Future<Boolean>> = spans.map { span ->
                pool.submit<Boolean> {
                    val ok = segment(url, tmp, span.first, span.second, headers, cancel, failed, done, total, onProgress)
                    if (!ok) failed.set(true)         // bir parça inmediyse diğerleri de boşuna sürmesin
                    ok
                }
            }
            val results = futures.map { try { it.get() } catch (e: Exception) { false } }
            if (cancel.get()) throw CancelledException()
            if (failed.get() || !results.all { it } || done.get() != total) return null
            val finalExt = ext.ifEmpty { extFromContentType(pr.contentType, fallbackExt) }
            pending = sink.begin(relStem, finalExt, mimeForExt(finalExt))
            tmp.inputStream().use { input ->
                val buf = ByteArray(CHUNK)
                while (true) {
                    if (cancel.get()) throw CancelledException()
                    val r = input.read(buf)
                    if (r < 0) break
                    pending.stream.write(buf, 0, r)
                }
            }
            val location = pending.commit()
            pending = null
            return Result(true, location, total, status = 206)
        } catch (e: CancelledException) {
            pending?.abort()
            throw e
        } catch (e: Exception) {
            pending?.abort()
            return null
        } finally {
            pool.shutdownNow()
            tmp.delete()
        }
    }

    private fun segment(
        url: String, tmp: File, start: Long, end: Long, headers: Map<String, String>, cancel: AtomicBoolean,
        failed: AtomicBoolean, done: AtomicLong, total: Long, onProgress: ((Long, Long) -> Unit)?,
    ): Boolean {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = true
                for ((k, v) in headers) setRequestProperty(k, v)
                setRequestProperty("Range", "bytes=$start-$end")
            }
            if (conn.responseCode != 206) return false
            var pos = start
            RandomAccessFile(tmp, "rw").use { raf ->
                raf.seek(start)
                conn.inputStream.use { input ->
                    val buf = ByteArray(CHUNK)
                    while (pos <= end) {
                        if (cancel.get() || failed.get()) return false
                        val r = input.read(buf, 0, minOf(buf.size.toLong(), end - pos + 1).toInt())
                        if (r < 0) return false
                        raf.write(buf, 0, r)
                        pos += r
                        onProgress?.invoke(done.addAndGet(r.toLong()), total)
                    }
                }
            }
            return pos == end + 1
        } catch (e: Exception) {
            return false
        } finally {
            conn?.disconnect()
        }
    }

    private fun fetchOne(
        url: String, relStem: String, ext: String, fallbackExt: String, headers: Map<String, String>,
        sink: MediaSink, cancel: AtomicBoolean, onProgress: ((Long, Long) -> Unit)?,
    ): Result {
        var conn: HttpURLConnection? = null
        var pending: PendingFile? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = true
                for ((k, v) in headers) setRequestProperty(k, v)
            }
            val status = conn.responseCode
            if (status !in 200..299) {
                return Result(false, error = msg("fetch.http", "code" to status), status = status)
            }
            val contentType = conn.contentType ?: ""
            val lowType = contentType.lowercase()
            if (lowType.startsWith("text/html") || lowType.startsWith("application/json") || lowType.startsWith("text/plain")) {
                return Result(false, error = msg("fetch.html_page"), status = 403)
            }
            val total = conn.contentLengthLong.coerceAtLeast(0)
            val finalExt = ext.ifEmpty { extFromContentType(contentType, fallbackExt) }
            pending = sink.begin(relStem, finalExt, mimeForExt(finalExt))
            var done = 0L
            conn.inputStream.use { input ->
                val buf = ByteArray(CHUNK)
                while (true) {
                    if (cancel.get()) throw CancelledException()
                    val n = input.read(buf)
                    if (n < 0) break
                    pending.stream.write(buf, 0, n)
                    done += n
                    onProgress?.invoke(done, total)
                }
            }
            if (done == 0L) {
                pending.abort(); pending = null
                return Result(false, error = msg("fetch.empty"), status = status)
            }
            if (total > 0 && done < total) {
                pending.abort(); pending = null
                return Result(false, error = msg("fetch.incomplete"), status = status)
            }
            val location = pending.commit()
            pending = null
            return Result(true, location, done, status = status)
        } catch (e: CancelledException) {
            pending?.abort()
            throw e
        } catch (e: UnknownHostException) {
            pending?.abort()
            return Result(false, error = msg("fetch.connection", "reason" to (e.message ?: "DNS")))
        } catch (e: SocketTimeoutException) {
            pending?.abort()
            return Result(false, error = msg("fetch.connection", "reason" to "timeout"))
        } catch (e: IOException) {
            pending?.abort()
            return Result(false, error = msg("fetch.connection", "reason" to (e.message ?: e.javaClass.simpleName)))
        } catch (e: Exception) {
            pending?.abort()
            return Result(false, error = msg("fetch.write", "error" to (e.message ?: e.javaClass.simpleName)))
        } finally {
            conn?.disconnect()
        }
    }
}
