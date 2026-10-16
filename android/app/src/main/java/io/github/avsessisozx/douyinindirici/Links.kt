package io.github.avsessisozx.douyinindirici

import java.net.URLDecoder

/**
 * Yapıştırılan metinden Douyin linkini bulur ve türünü anlar.
 * Windows sürümündeki douyin_indirici/links.py'nin birebir karşılığı;
 * ikisi de tests/vectors/links.json ile test edilir.
 */
object Links {
    const val PROFILE = "profile"
    const val POST = "post"
    const val SHORT = "short"

    data class Parsed(
        val kind: String?,
        val url: String = "",
        val awemeId: String = "",
        val secUid: String = "",
        val error: Msg? = null,
    ) {
        val ok: Boolean get() = kind != null
        fun describe(): Msg = when (kind) {
            PROFILE -> msg("link.profile_detected")
            POST -> msg("link.post_detected")
            SHORT -> msg("link.short_detected")
            else -> error ?: msg("link.no_target")
        }
    }

    private val URL_RE = Rx.unicode("(?:https?://)?(?:[a-z0-9-]+\\.)*(?:douyin|iesdouyin)\\.com[^\\s，。！？、\"'<>]*", ignoreCase = true)
    private val ID_RE = Rx.unicode("^\\d{15,22}$")
    private val SEC_UID_RE = Regex("^[A-Za-z0-9_\\-]{20,}$")
    private val USER_PATH = Regex("^/(?:share/)?user/([^/?#]+)")
    private val POST_PATH = Regex("^/(?:share/)?(video|note|slides|gallery)/(\\d+)")
    private val FINAL_POST = Regex("/(?:share/)?(?:video|note|slides|gallery)/(\\d+)")
    private val FINAL_USER = Regex("/(?:share/)?user/([A-Za-z0-9_\\-]{20,})")

    private class UrlParts(val host: String, val path: String, val query: Map<String, List<String>>)

    private fun isDigits(s: String) = s.isNotEmpty() && s.all { it.isDigit() }

    private fun splitUrl(url: String): UrlParts {
        var rest = url.substringAfter("://", url)
        val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) rest.length else it }
        val authority = rest.substring(0, authorityEnd)
        rest = rest.substring(authorityEnd)
        val host = authority.substringAfterLast('@').substringBefore(':').lowercase()
        val noFragment = rest.substringBefore('#')
        val path = noFragment.substringBefore('?').ifEmpty { "/" }
        val queryString = if ('?' in noFragment) noFragment.substringAfter('?') else ""
        val query = LinkedHashMap<String, MutableList<String>>()
        for (pair in queryString.split('&')) {
            val eq = pair.indexOf('=')
            if (eq < 0) continue
            val name = decode(pair.substring(0, eq))
            val value = decode(pair.substring(eq + 1))
            if (value.isEmpty()) continue
            query.getOrPut(name) { mutableListOf() }.add(value)
        }
        return UrlParts(host, path, query)
    }

    private fun decode(s: String): String = try {
        URLDecoder.decode(s, "UTF-8")
    } catch (e: Exception) {
        s
    }

    private fun firstQuery(q: Map<String, List<String>>, key: String): String =
        q[key]?.firstOrNull()?.trim() ?: ""

    fun parse(textIn: String?): Parsed {
        val text = (textIn ?: "").trim()
        if (text.isEmpty()) return Parsed(null, error = msg("link.empty"))
        if (ID_RE.matches(text)) return Parsed(POST, url = "https://www.douyin.com/video/$text", awemeId = text)

        val match = URL_RE.find(text)
        if (match == null) {
            if ("tiktok.com" in text.lowercase()) return Parsed(null, error = msg("link.tiktok"))
            return Parsed(null, error = msg("link.not_found"))
        }
        var url = match.value.trimEnd('.', ',', ';', ')', ']', '}')
        if (!url.lowercase().startsWith("http")) url = "https://$url"
        val parts = splitUrl(url)
        val host = parts.host
        val path = parts.path
        val qs = parts.query

        if (host == "v.douyin.com" || host == "v.iesdouyin.com") {
            if (path.trim('/').length < 3) return Parsed(null, error = msg("link.short_incomplete"))
            return Parsed(SHORT, url = url)
        }
        if (host == "live.douyin.com" || "/live/" in path || path.startsWith("/follow/live")) {
            return Parsed(null, error = msg("link.live"))
        }

        var modalId = firstQuery(qs, "modal_id")
        if (modalId.isNotEmpty() && !isDigits(modalId)) modalId = ""

        USER_PATH.find(path)?.let { m ->
            val secUid = m.groupValues[1]
            if (secUid == "self") {
                if (modalId.isNotEmpty()) return Parsed(POST, url = "https://www.douyin.com/video/$modalId", awemeId = modalId)
                return Parsed(null, error = msg("link.self"))
            }
            if (!SEC_UID_RE.matches(secUid)) return Parsed(null, error = msg("link.profile_broken"))
            if (modalId.isNotEmpty()) {
                return Parsed(POST, url = "https://www.douyin.com/video/$modalId", awemeId = modalId, secUid = secUid)
            }
            return Parsed(PROFILE, url = "https://www.douyin.com/user/$secUid", secUid = secUid)
        }

        POST_PATH.find(path)?.let { m ->
            val awemeId = m.groupValues[2]
            val kindPath = if (m.groupValues[1] == "video") "video" else "note"
            return Parsed(POST, url = "https://www.douyin.com/$kindPath/$awemeId", awemeId = awemeId)
        }

        if (modalId.isNotEmpty()) return Parsed(POST, url = "https://www.douyin.com/video/$modalId", awemeId = modalId)

        for (key in listOf("aweme_id", "vid", "item_id")) {
            val value = firstQuery(qs, key)
            if (isDigits(value) && value.length >= 15) {
                return Parsed(POST, url = "https://www.douyin.com/video/$value", awemeId = value)
            }
        }

        if (Regex("^/(?:collection|mix)/").containsMatchIn(path)) return Parsed(null, error = msg("link.collection"))
        if (path.startsWith("/music/")) return Parsed(null, error = msg("link.music"))
        return Parsed(null, error = msg("link.no_target"))
    }

    /** Kısa link açıldıktan sonra tarayıcının vardığı adresi yorumlar. */
    fun classifyFinalUrl(url: String): Parsed {
        val parsed = parse(url)
        if (parsed.ok && parsed.kind != SHORT) return parsed
        FINAL_POST.find(url)?.let { return Parsed(POST, url = url, awemeId = it.groupValues[1]) }
        FINAL_USER.find(url)?.let { return Parsed(PROFILE, url = url, secUid = it.groupValues[1]) }
        return Parsed(null, error = msg("link.short_bad"))
    }
}
