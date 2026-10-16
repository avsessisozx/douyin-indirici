package io.github.avsessisozx.douyinindirici

import org.json.JSONArray
import org.json.JSONObject

/**
 * Douyin gönderi verisinden indirilecek dosyaları çıkarır.
 * Windows sürümündeki douyin_indirici/media.py'nin birebir karşılığı;
 * ikisi de tests/vectors/posts.json ile test edilir.
 *
 * Python'un "doğruluk" kuralları (0, "", [], {} ve None yanlış sayılır) burada
 * truthy() ile taklit edilir, çünkü kalite seçimi bu kurallara dayanıyor.
 */
object Media {
    const val QUALITY_HIGHEST = "highest"
    const val QUALITY_LOWEST = "lowest"
    val QUALITY_CHOICES = listOf(QUALITY_HIGHEST, "1080p", "720p", "540p", QUALITY_LOWEST)

    const val CONTENT_ALL = "all"
    const val CONTENT_VIDEO = "video"
    const val CONTENT_IMAGE = "image"

    private val WATERMARK_HINTS = listOf("tplv-dy-water", "dy-water", "owner_watermark", "watermark_image", "watermark=1", "playwm")
    private val GALLERY_TYPES = setOf(2, 68, 150)

    data class MediaFile(val urls: List<String>, val relStem: String, val kind: String, val ext: String = "", val expectedSize: Long = 0)

    class Post(
        val awemeId: String,
        val desc: String,
        val createTime: Long,
        val authorName: String,
        val authorSecUid: String,
        val isGallery: Boolean,
    ) {
        val files = mutableListOf<MediaFile>()
        var skippedReason: Msg? = null
        val stem: String get() = Naming.postStem(awemeId, desc, createTime)
    }

    data class VideoTier(val urls: List<String>, val width: Int, val height: Int, val bitrate: Long, val h265: Boolean, val size: Long) {
        val shortEdge: Int get() = if (width != 0 && height != 0) minOf(width, height) else maxOf(width, height)
        val pixels: Long get() = width.toLong() * height.toLong()
    }

    // ------------------------------------------------------------ yardımcılar

    private fun isEmptyValue(v: Any?): Boolean = when (v) {
        null, JSONObject.NULL -> true
        is String -> v.isEmpty()
        is JSONArray -> v.length() == 0
        is JSONObject -> v.length() == 0
        else -> false
    }

    /** Python: _get(d, *keys) — boş olmayan ilk değer. */
    fun get(d: Any?, vararg keys: String): Any? {
        if (d !is JSONObject) return null
        for (k in keys) {
            if (d.has(k)) {
                val v = d.opt(k)
                if (!isEmptyValue(v)) return v
            }
        }
        return null
    }

    /** Python doğruluğu: None, 0, "", [], {} ve False yanlıştır. */
    private fun truthy(v: Any?): Boolean = when (v) {
        null, JSONObject.NULL -> false
        is Boolean -> v
        is Number -> v.toDouble() != 0.0
        is String -> v.isNotEmpty()
        is JSONArray -> v.length() > 0
        is JSONObject -> v.length() > 0
        else -> true
    }

    private fun or(a: Any?, b: Any?): Any? = if (truthy(a)) a else b

    /** Python: str(value) — True/False ve sayılar Python gibi yazılır. */
    private fun pyStr(v: Any?): String = when (v) {
        null, JSONObject.NULL -> "None"
        is Boolean -> if (v) "True" else "False"
        is Double -> if (v == Math.floor(v) && !v.isInfinite() && Math.abs(v) < 1e16) "${v.toLong()}.0" else v.toString()
        else -> v.toString()
    }

    /** Python: int(float(str(value))), hata olursa 0. */
    fun toInt(v: Any?): Long {
        if (v is Boolean || v == null || v == JSONObject.NULL) return 0
        if (v is Int) return v.toLong()
        if (v is Long) return v
        val d = pyStr(v).trim().toDoubleOrNull() ?: return 0
        if (d.isNaN() || d.isInfinite()) return 0
        return d.toLong()
    }

    private fun normUrl(u: String): String {
        val s = u.trim()
        return if (s.startsWith("//")) "https:$s" else s
    }

    fun urlsOf(source: Any?): List<String> {
        val out = mutableListOf<String>()
        when (source) {
            is String -> if (source.isNotBlank()) out += normUrl(source)
            is JSONObject -> {
                val lst = get(source, "url_list", "urlList")
                if (lst is JSONArray) {
                    for (i in 0 until lst.length()) {
                        val u = lst.opt(i)
                        if (u is String && u.isNotBlank()) out += normUrl(u)
                    }
                } else {
                    val single = get(source, "src", "url")
                    if (single is String) out += normUrl(single)
                }
            }
            is JSONArray -> for (i in 0 until source.length()) out += urlsOf(source.opt(i))
        }
        val seen = LinkedHashSet<String>()
        for (u in out) if (u.startsWith("http")) seen += u
        return seen.toList()
    }

    fun isWatermarked(url: String): Boolean {
        val low = url.lowercase()
        return WATERMARK_HINTS.any { it in low }
    }

    // ------------------------------------------------------------ video

    private fun isH265(entry: JSONObject, playAddr: Any?): Boolean {
        for (k in listOf("is_h265", "isH265", "is_bytevc1", "isBytevc1")) {
            if (toInt(get(entry, k)) == 1L) return true
        }
        for (k in listOf("codec_type", "CodecType", "codecType", "gear_name", "gearName", "format")) {
            val v = get(entry, k)
            val value = (if (truthy(v)) pyStr(v) else "").lowercase()
            if ("265" in value || "bytevc1" in value || "hevc" in value) return true
        }
        for (u in urlsOf(playAddr).take(1)) {
            val low = u.lowercase()
            if ("bytevc1" in low || "h265" in low) return true
        }
        return false
    }

    fun videoTiers(video: JSONObject): List<VideoTier> {
        val tiers = mutableListOf<VideoTier>()
        val entries = get(video, "bit_rate", "bitRateList")
        if (entries is JSONArray) {
            for (i in 0 until entries.length()) {
                val entry = entries.opt(i) as? JSONObject ?: continue
                val playAddr = get(entry, "play_addr", "playAddr")
                val urls = urlsOf(playAddr).filter { !isWatermarked(it) }
                if (urls.isEmpty()) continue
                val pa = playAddr as? JSONObject ?: JSONObject()
                tiers += VideoTier(
                    urls = urls,
                    width = toInt(or(get(pa, "width"), get(entry, "width"))).toInt(),
                    height = toInt(or(get(pa, "height"), get(entry, "height"))).toInt(),
                    bitrate = toInt(get(entry, "bit_rate", "bitRate")),
                    h265 = isH265(entry, playAddr),
                    size = toInt(or(get(pa, "data_size", "dataSize"), get(entry, "data_size", "dataSize"))),
                )
            }
        }
        return tiers
    }

    private fun fallbackTiers(video: JSONObject): List<VideoTier> {
        val tiers = mutableListOf<VideoTier>()
        val width = toInt(get(video, "width")).toInt()
        val height = toInt(get(video, "height")).toInt()
        for ((key, h265) in listOf("play_addr_h264" to false, "play_addr" to false, "playAddr" to false,
            "playApi" to false, "play_addr_265" to true)) {
            val source = video.opt(key)
            val urls = urlsOf(source).filter { !isWatermarked(it) }
            if (urls.isNotEmpty()) {
                val pa = source as? JSONObject ?: JSONObject()
                val w = toInt(get(pa, "width")).toInt().let { if (it != 0) it else width }
                val h = toInt(get(pa, "height")).toInt().let { if (it != 0) it else height }
                tiers += VideoTier(urls, w, h, 0, h265, toInt(get(pa, "data_size", "dataSize")))
            }
        }
        return tiers
    }

    fun chooseVideoUrls(video: Any?, quality: String = QUALITY_HIGHEST, compat: Boolean = true): VideoTier? {
        if (video !is JSONObject) return null
        val tiers = videoTiers(video)
        val fallback = fallbackTiers(video)
        var pool = tiers.ifEmpty { fallback }
        if (pool.isEmpty()) return null
        if (compat) {
            val h264 = pool.filter { !it.h265 }
            if (h264.isNotEmpty()) pool = h264
        }
        val q = quality.ifEmpty { QUALITY_HIGHEST }.lowercase()
        val big = 1_000_000_000_000L
        val chosen: VideoTier = if (q == QUALITY_LOWEST) {
            pool.minWithOrNull(compareBy<VideoTier>({ if (it.bitrate != 0L) it.bitrate else big }, { if (it.pixels != 0L) it.pixels else big }))!!
        } else if (q.endsWith("p") && q.dropLast(1).let { s -> s.isNotEmpty() && s.all { it.isDigit() } }) {
            val target = q.dropLast(1).toInt()
            pool.minWithOrNull(compareBy<VideoTier>(
                { if (it.shortEdge != 0) Math.abs(it.shortEdge - target) else 1_000_000 },
                { -it.bitrate },
                { -it.pixels },
            ))!!
        } else {
            pool.maxWithOrNull(compareBy<VideoTier>({ it.pixels }, { it.bitrate }))!!
        }
        val extra = mutableListOf<String>()
        for (t in fallback) {
            if (compat && t.h265 && !chosen.h265) continue
            extra += t.urls
        }
        val urls = LinkedHashSet<String>().apply { addAll(chosen.urls); addAll(extra) }.toList()
        return VideoTier(urls, chosen.width, chosen.height, chosen.bitrate, chosen.h265, chosen.size)
    }

    // ------------------------------------------------------------ resimler

    private fun dictsOf(arr: JSONArray): List<JSONObject> =
        (0 until arr.length()).mapNotNull { arr.opt(it) as? JSONObject }

    fun galleryItems(item: Any?): List<JSONObject> {
        if (item !is JSONObject) return emptyList()
        val info = item.opt("image_post_info")
        if (info is JSONObject) {
            for (k in listOf("images", "image_list")) {
                val v = info.opt(k)
                if (v is JSONArray && v.length() > 0) return dictsOf(v)
            }
        }
        for (k in listOf("images", "image_list", "imageList")) {
            val v = item.opt(k)
            if (v is JSONArray && v.length() > 0) return dictsOf(v)
        }
        return emptyList()
    }

    /** Python urlparse(url).path ile aynı: sorgu, parça ve son bölümdeki ";parametre" atılır. */
    private fun urlPath(url: String): String {
        val afterScheme = url.substringAfter("://", url)
        val slash = afterScheme.indexOfFirst { it == '/' || it == '?' || it == '#' }
        if (slash < 0 || afterScheme[slash] != '/') return ""
        var path = afterScheme.substring(slash).substringBefore('#').substringBefore('?')
        val lastSlash = path.lastIndexOf('/')
        val semi = path.indexOf(';', lastSlash + 1)
        if (semi >= 0) path = path.substring(0, semi)
        return path
    }

    private fun urlExt(url: String): String {
        val path = urlPath(url).lowercase()
        for (ext in listOf(".jpeg", ".jpg", ".png", ".webp", ".heic", ".avif", ".gif")) {
            if (ext in path) return ext
        }
        return ""
    }

    fun imageUrls(image: JSONObject): List<String> {
        data class Ranked(val wm: Int, val rank: Int, val fmt: Int, val idx: Int, val url: String)
        val ranked = mutableListOf<Ranked>()
        val sources = listOf(
            get(image, "watermark_free_download_url_list") to 0,
            image to 1,
            get(image, "origin_image", "originImage") to 1,
            get(image, "display_image", "displayImage") to 2,
            get(image, "download_url_list", "downloadUrlList") to 5,
            get(image, "download_url", "downloadUrl") to 5,
        )
        for ((source, rank) in sources) {
            for (u in urlsOf(source)) {
                val ext = urlExt(u)
                val fmt = when (ext) {
                    ".jpeg", ".jpg", ".png" -> 0
                    ".webp" -> 1
                    else -> 2
                }
                ranked += Ranked(if (isWatermarked(u)) 1 else 0, rank, fmt, ranked.size, u)
            }
        }
        val sorted = ranked.sortedWith(compareBy({ it.wm }, { it.rank }, { it.fmt }, { it.idx }))
        val clean = sorted.filter { it.wm == 0 }.map { it.url }
        val marked = sorted.filter { it.wm == 1 }.map { it.url }
        return LinkedHashSet(clean.ifEmpty { marked }).toList()
    }

    // ------------------------------------------------------------ gönderi

    fun authorOf(item: JSONObject): Pair<String, String> {
        val author = item.opt("author") as? JSONObject ?: JSONObject()
        val name = get(author, "nickname").let { if (truthy(it)) pyStr(it) else "" }.trim()
        val secUid = get(author, "sec_uid", "secUid").let { if (truthy(it)) pyStr(it) else "" }.trim()
        return name to secUid
    }

    fun awemeIdOf(item: Any?): String {
        val v = get(item, "aweme_id", "awemeId")
        return (if (truthy(v)) pyStr(v) else "").trim()
    }

    fun isGallery(item: JSONObject): Boolean {
        if (galleryItems(item).isNotEmpty()) return true
        val video = item.opt("video") as? JSONObject ?: JSONObject()
        return toInt(get(item, "aweme_type", "awemeType")).toInt() in GALLERY_TYPES && chooseVideoUrls(video) == null
    }

    fun buildPost(item: JSONObject, content: String = CONTENT_ALL, quality: String = QUALITY_HIGHEST, compat: Boolean = true): Post {
        val (name, secUid) = authorOf(item)
        val desc = get(item, "desc").let { if (truthy(it)) pyStr(it) else "" }
        val post = Post(
            awemeId = awemeIdOf(item),
            desc = desc,
            createTime = toInt(get(item, "create_time", "createTime")),
            authorName = name,
            authorSecUid = secUid,
            isGallery = isGallery(item),
        )
        val stem = post.stem

        if (post.isGallery) {
            if (content == CONTENT_VIDEO) {
                post.skippedReason = msg("skip.gallery_video_only")
                return post
            }
            val images = galleryItems(item)
            val width = maxOf(2, images.size.toString().length)
            images.forEachIndexed { i, image ->
                val num = (i + 1).toString().padStart(width, '0')
                val urls = imageUrls(image)
                if (urls.isNotEmpty()) post.files += MediaFile(urls, "$stem/$num", "image")
                val live = image.opt("video") as? JSONObject
                if (live != null && live.length() > 0) {
                    chooseVideoUrls(live, quality, compat)?.let { tier ->
                        post.files += MediaFile(tier.urls, "$stem/${num}_hareketli", "live", ".mp4", tier.size)
                    }
                }
            }
            if (post.files.isEmpty()) post.skippedReason = msg("skip.no_image")
            return post
        }

        if (content == CONTENT_IMAGE) {
            post.skippedReason = msg("skip.video_image_only")
            return post
        }
        val video = item.opt("video") as? JSONObject ?: JSONObject()
        val tier = chooseVideoUrls(video, quality, compat)
        if (tier == null) {
            post.skippedReason = msg("skip.no_video")
            return post
        }
        post.files += MediaFile(tier.urls, stem, "video", ".mp4", tier.size)
        return post
    }

    fun authorFolder(name: String, secUid: String): String =
        Naming.sanitize(name, 40, fallback = "").ifEmpty { Naming.sanitize(secUid.take(16), fallback = "douyin") }

    /** Herhangi bir JSON ağacında gönderi gibi görünen nesneleri bulur. */
    fun findPostDicts(data: Any?, awemeId: String = "", limit: Int = 500): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        val stack = ArrayDeque<Any?>()
        stack.addLast(data)
        var seen = 0
        while (stack.isNotEmpty() && seen < 200_000) {
            val node = stack.removeLast()
            seen++
            when (node) {
                is JSONObject -> {
                    val id = awemeIdOf(node)
                    if (id.isNotEmpty() && (awemeId.isEmpty() || id == awemeId) &&
                        (node.opt("video") is JSONObject || galleryItems(node).isNotEmpty())) {
                        out += node
                        if (out.size >= limit) return out
                        continue
                    }
                    for (k in node.keys()) stack.addLast(node.opt(k))
                }
                is JSONArray -> for (i in 0 until node.length()) stack.addLast(node.opt(i))
            }
        }
        return out
    }
}
