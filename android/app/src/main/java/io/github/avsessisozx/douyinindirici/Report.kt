package io.github.avsessisozx.douyinindirici

import org.json.JSONArray
import org.json.JSONObject

/**
 * Ayrıntılı rapor: sorunu uzaktan incelemek için sürüm, telefon, ayarlar, durum ve günlük tek metinde.
 * Çerez, oturum anahtarı, şifre ve erişim bilgisi içermez (varsa silinir). Windows'taki report.py'nin karşılığı.
 */
object Report {
    const val MAX_CHARS = 150_000

    private val SECRET = Regex(
        "(?i)(sessionid|sessionid_ss|sid_tt|sid_guard|uid_tt|passport_csrf_token|ttwid|odin_tt|msToken|a_bogus|" +
            "authorization|password|passwd|token|cookie)([\"']?\\s*[:=]\\s*[\"']?)[^;\\s&,\"'}]+"
    )

    fun redact(text: String): String = SECRET.replace(text) { m -> "${m.groupValues[1]}${m.groupValues[2]}[silindi]" }

    /** {"key":..., "params":{...}} biçimindeki mesajı metne çevirir. */
    fun renderJson(m: Any?, translate: (String, Map<String, Any?>) -> String): String {
        if (m !is JSONObject) return m?.toString() ?: ""
        val params = LinkedHashMap<String, Any?>()
        val p = m.optJSONObject("params")
        if (p != null) for (k in p.keys()) {
            val v = p.opt(k)
            params[k] = if (v is JSONObject && v.has("key")) renderJson(v, translate) else if (v == JSONObject.NULL) null else v
        }
        return translate(m.optString("key"), params)
    }

    fun build(
        info: List<Pair<String, String>>,
        settings: JSONObject,
        snapshot: JSONObject,
        translate: (String, Map<String, Any?>) -> String,
    ): String {
        val out = ArrayList<String>()
        out += "Douyin İndirici raporu"
        for ((k, v) in info) out += "$k: $v"
        out += "\n--- Ayarlar ---"
        val s = JSONObject(settings.toString()); s.remove("out_dir")
        out += s.toString()

        out += "\n--- Durum ---"
        for (k in listOf("running", "paused", "phase", "mode", "captcha", "login_wall", "author", "found_posts", "posts_done",
            "posts_skipped", "posts_known", "files_total", "files_done", "files_skipped", "files_failed", "failed_posts")) {
            out += "$k: ${snapshot.opt(k)}"
        }
        val summary = snapshot.optJSONObject("summary")
        if (summary != null) out += "summary: " + renderJson(summary, translate)

        val logs: JSONArray = snapshot.optJSONArray("logs") ?: JSONArray()
        val lines = (0 until logs.length()).map { i ->
            val e = logs.getJSONObject(i)
            val m = e.optJSONObject("msg")
            val text = if (m != null) renderJson(m, translate) else e.optString("text")
            "${e.optString("t")} [${e.optString("level")}] ${text.replace("\n", " ")}"
        }
        out += "\n--- Günlük (${lines.size} satır) ---"
        val head = out.sumOf { it.length + 1 }
        val body = ArrayList<String>()
        var size = 0
        var truncated = false
        for (line in lines.asReversed()) {            // sığmazsa en eski satırlar atılır
            if (head + size + line.length + 1 > MAX_CHARS) { truncated = true; break }
            body.add(0, line)
            size += line.length + 1
        }
        if (truncated) out += "(başı kısaltıldı)"
        out.addAll(body)
        return redact(out.joinToString("\n") + "\n")
    }
}
