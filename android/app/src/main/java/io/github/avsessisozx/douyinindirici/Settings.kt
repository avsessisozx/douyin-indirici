package io.github.avsessisozx.douyinindirici

import org.json.JSONObject

/** Kullanıcı ayarları. Windows sürümündeki paths.clean_settings ile aynı kurallar. */
data class SettingsValues(
    val content: String = Media.CONTENT_ALL,
    val quality: String = Media.QUALITY_HIGHEST,
    val compat: Boolean = true,
    val limit: Int = 0,
    val lang: String = "auto",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("out_dir", "")
        .put("content", content)
        .put("quality", quality)
        .put("compat", compat)
        .put("limit", limit)
        .put("lang", lang)

    /** Gelen JSON'daki geçerli alanları üzerine yazar, geçersizleri yok sayar. */
    fun merge(o: JSONObject?, languages: Collection<String>): SettingsValues {
        if (o == null) return this
        var v = this
        val c = o.opt("content")
        if (c is String && c in listOf(Media.CONTENT_ALL, Media.CONTENT_VIDEO, Media.CONTENT_IMAGE)) v = v.copy(content = c)
        val q = o.opt("quality")
        if (q is String && q in Media.QUALITY_CHOICES) v = v.copy(quality = q)
        if (o.has("compat")) {
            val b = o.opt("compat")
            v = v.copy(compat = when (b) {
                is Boolean -> b
                is Number -> b.toInt() != 0
                is String -> b.isNotEmpty() && b != "false" && b != "0"
                else -> true
            })
        }
        if (o.has("limit")) {
            val l = Media.toInt(o.opt("limit")).coerceIn(0, 100_000).toInt()
            v = v.copy(limit = l)
        }
        val lang = o.opt("lang")
        if (lang is String) v = v.copy(lang = if (lang in languages) lang else "auto")
        return v
    }

    companion object {
        fun fromJson(raw: String?, languages: Collection<String>): SettingsValues = try {
            SettingsValues().merge(if (raw.isNullOrEmpty()) null else JSONObject(raw), languages)
        } catch (e: Exception) {
            SettingsValues()
        }
    }
}

/** Testlerin uygulamayı sahte Douyin sunucusuna yönlendirmesi için (normal kullanımda hep boş). */
object TestHooks {
    @Volatile var baseUrl: String? = null
    @Volatile var idleSeconds: Double? = null
    @Volatile var sinkFactory: (() -> MediaSink)? = null
}
