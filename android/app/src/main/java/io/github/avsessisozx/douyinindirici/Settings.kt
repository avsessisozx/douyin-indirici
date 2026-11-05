package io.github.avsessisozx.douyinindirici

import org.json.JSONObject

/** Kullanıcı ayarları. Windows sürümündeki paths.clean_settings ile aynı kurallar. */
data class SettingsValues(
    val content: String = Media.CONTENT_ALL,
    val quality: String = Media.QUALITY_HIGHEST,
    val compat: Boolean = true,
    val limit: Int = 0,
    val history: Boolean = true,
    val parallel: Int = 3,
    val chunks: Int = 4,
    val sound: Boolean = true,
    val lang: String = "auto",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("out_dir", "")
        .put("content", content)
        .put("quality", quality)
        .put("compat", compat)
        .put("limit", limit)
        .put("history", history)
        .put("parallel", parallel)
        .put("chunks", chunks)
        .put("sound", sound)
        .put("lang", lang)

    /** Gelen JSON'daki geçerli alanları üzerine yazar, geçersizleri yok sayar. */
    fun merge(o: JSONObject?, languages: Collection<String>): SettingsValues {
        if (o == null) return this
        var v = this
        val c = o.opt("content")
        if (c is String && c in listOf(Media.CONTENT_ALL, Media.CONTENT_VIDEO, Media.CONTENT_IMAGE)) v = v.copy(content = c)
        val q = o.opt("quality")
        if (q is String && q in Media.QUALITY_CHOICES) v = v.copy(quality = q)
        if (o.has("compat")) v = v.copy(compat = bool(o.opt("compat")))
        if (o.has("history")) v = v.copy(history = bool(o.opt("history")))
        if (o.has("sound")) v = v.copy(sound = bool(o.opt("sound")))
        if (o.has("parallel")) v = v.copy(parallel = choice(o.opt("parallel"), PARALLEL_CHOICES, v.parallel))
        if (o.has("chunks")) v = v.copy(chunks = choice(o.opt("chunks"), CHUNK_CHOICES, v.chunks))
        if (o.has("limit")) {
            val l = Media.toInt(o.opt("limit")).coerceIn(0, 100_000).toInt()
            v = v.copy(limit = l)
        }
        val lang = o.opt("lang")
        if (lang is String) v = v.copy(lang = if (lang in languages) lang else "auto")
        return v
    }

    companion object {
        val PARALLEL_CHOICES = listOf(1, 2, 3, 4, 6, 8)
        val CHUNK_CHOICES = listOf(1, 2, 4, 6, 8)

        private fun bool(b: Any?): Boolean = when (b) {
            is Boolean -> b
            is Number -> b.toInt() != 0
            is String -> b.isNotEmpty() && b != "false" && b != "0"
            else -> true
        }

        private fun choice(v: Any?, allowed: List<Int>, default: Int): Int {
            val n = when (v) {
                is Number -> v.toInt()
                is String -> v.trim().toIntOrNull()
                else -> null
            }
            return if (n != null && n in allowed) n else default
        }

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
    @Volatile var history: HistoryStore? = null
}
