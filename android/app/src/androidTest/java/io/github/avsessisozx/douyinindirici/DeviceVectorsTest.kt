package io.github.avsessisozx.douyinindirici

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Bilgisayardaki SharedVectorsTest'in aynısı, ama telefonda (emülatörde) çalışır:
 * Android'in düzenli ifade ve JSON motorları masaüstü Java'dan farklı davranabilir.
 */
@RunWith(AndroidJUnit4::class)
class DeviceVectorsTest {
    private fun load(name: String) = InstrumentationRegistry.getInstrumentation().context.assets
        .open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun nul(o: JSONObject, k: String): String? = if (o.isNull(k)) null else o.getString(k)

    @Test
    fun links() {
        val data = JSONObject(load("links.json"))
        val cases = data.getJSONArray("parse")
        var checked = 0
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val p = Links.parse(c.getString("input"))
            val label = c.getString("input")
            assertEquals(label, nul(c, "kind"), p.kind)
            assertEquals(label, c.getString("url"), p.url)
            assertEquals(label, c.getString("aweme_id"), p.awemeId)
            assertEquals(label, c.getString("sec_uid"), p.secUid)
            assertEquals(label, nul(c, "error"), p.error?.key)
            assertEquals(label, c.getString("describe"), p.describe().key)
            checked++
        }
        val finals = data.getJSONArray("final_url")
        for (i in 0 until finals.length()) {
            val c = finals.getJSONObject(i)
            val p = Links.classifyFinalUrl(c.getString("input"))
            assertEquals(c.getString("input"), nul(c, "kind"), p.kind)
            assertEquals(c.getString("aweme_id"), p.awemeId)
            assertEquals(c.getString("sec_uid"), p.secUid)
        }
        assert(checked > 20)
    }

    @Test
    fun posts() {
        val cases = JSONArray(load("posts.json"))
        var checked = 0
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val item = case.getJSONObject("item")
            val results = case.getJSONArray("results")
            for (j in 0 until results.length()) {
                val r = results.getJSONObject(j)
                val label = "${case.getString("name")} ${r.getString("content")} ${r.getString("quality")} ${r.getBoolean("compat")}"
                val post = Media.buildPost(item, r.getString("content"), r.getString("quality"), r.getBoolean("compat"))
                assertEquals(label, r.getString("aweme_id"), post.awemeId)
                assertEquals(label, r.getString("author_name"), post.authorName)
                assertEquals(label, r.getString("author_sec_uid"), post.authorSecUid)
                assertEquals(label, r.getBoolean("is_gallery"), post.isGallery)
                assertEquals(label, nul(r, "skipped"), post.skippedReason?.key)
                assertEquals(label, r.getString("stem_tail"), post.stem.substringAfter('_'))
                val files = r.getJSONArray("files")
                assertEquals(label, files.length(), post.files.size)
                for (k in 0 until files.length()) {
                    val f = files.getJSONObject(k)
                    val g = post.files[k]
                    assertEquals("$label file $k", f.getString("rel_tail"), g.relStem.substringAfter('_'))
                    assertEquals("$label file $k", f.getString("kind"), g.kind)
                    assertEquals("$label file $k", f.getString("ext"), g.ext)
                    assertEquals("$label file $k", f.getLong("size"), g.expectedSize)
                    val urls = f.getJSONArray("urls")
                    assertEquals("$label file $k urls", (0 until urls.length()).map { urls.getString(it) }, g.urls)
                }
                checked++
            }
        }
        assert(checked > 100)
    }

    @Test
    fun naming() {
        val data = JSONObject(load("naming.json"))
        val s = data.getJSONArray("sanitize")
        for (i in 0 until s.length()) {
            val c = s.getJSONObject(i)
            val input: Any? = if (c.isNull("input")) null else c.getString("input")
            assertEquals(input.toString(), c.getString("output"), Naming.sanitize(input, c.getInt("max_len")))
        }
        val t = data.getJSONArray("title")
        for (i in 0 until t.length()) {
            val c = t.getJSONObject(i)
            assertEquals(c.getString("input"), c.getString("output"), Naming.titleFromDesc(c.getString("input")))
        }
        val a = data.getJSONArray("author_folder")
        for (i in 0 until a.length()) {
            val c = a.getJSONObject(i)
            assertEquals(c.getString("output"), Media.authorFolder(c.getString("name"), c.getString("sec_uid")))
        }
    }
}
