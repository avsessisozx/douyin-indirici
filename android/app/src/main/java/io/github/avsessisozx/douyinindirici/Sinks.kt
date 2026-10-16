package io.github.avsessisozx.douyinindirici

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

private val KNOWN_EXTS = listOf(".mp4", ".jpg", ".jpeg", ".png", ".webp", ".heic", ".avif", ".gif")

/**
 * Galeriye kaydeder: DCIM/Douyin/<Kişi>/...
 *
 * Windows'ta resimli gönderiler kendi alt klasörüne iner ("<gönderi>/01.jpg").
 * Galeri uygulamaları her alt klasörü ayrı albüm gösterdiği için Android'de
 * bunlar kişinin klasörüne düzleştirilir: "<gönderi>_01.jpg".
 */
class MediaStoreSink(private val ctx: Context) : MediaSink {
    private val resolver = ctx.contentResolver

    private fun split(relStem: String): Pair<String, String> {
        val first = relStem.substringBefore('/')
        val rest = relStem.substringAfter('/', "").replace('/', '_')
        return "${BASE}$first/" to rest.ifEmpty { first }
    }

    override fun exists(relStem: String, ext: String): Boolean {
        val (dir, name) = split(relStem)
        val names = if (ext.isNotEmpty()) listOf(name + ext) else KNOWN_EXTS.map { name + it }
        val placeholders = names.joinToString(",") { "?" }
        val args = arrayOf(dir, *names.toTypedArray())
        return try {
            resolver.query(
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.SIZE),
                "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME} IN ($placeholders) " +
                    "AND ${MediaStore.MediaColumns.IS_PENDING}=0",
                args, null,
            )?.use { c ->
                while (c.moveToNext()) if (c.getLong(1) > 0) return true
                false
            } ?: false
        } catch (e: Exception) {
            false
        }
    }

    override fun begin(relStem: String, ext: String, mime: String): PendingFile {
        val (dir, name) = split(relStem)
        val collection = if (mime.startsWith("video/")) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name + ext)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, dir)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri: Uri = resolver.insert(collection, values) ?: throw java.io.IOException("MediaStore insert failed")
        val out = try {
            resolver.openOutputStream(uri, "w") ?: throw java.io.IOException("openOutputStream failed")
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        val stream = BufferedOutputStream(out, 256 * 1024)
        return object : PendingFile {
            override val stream: OutputStream = stream
            override fun commit(): String {
                stream.close()
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                return uri.toString()
            }
            override fun abort() {
                try { stream.close() } catch (e: Exception) {}
                try { resolver.delete(uri, null, null) } catch (e: Exception) {}
            }
        }
    }

    companion object {
        const val BASE = "DCIM/Douyin/"
    }
}

/** Düz klasöre kaydeder (testler için). Windows sürümüyle aynı klasör yapısı. */
class FileSink(private val base: File) : MediaSink {
    override fun exists(relStem: String, ext: String): Boolean {
        val stem = File(base, relStem)
        val candidates = if (ext.isNotEmpty()) listOf(ext) else KNOWN_EXTS
        return candidates.any { File(stem.parentFile, stem.name + it).let { f -> f.isFile && f.length() > 0 } }
    }

    override fun begin(relStem: String, ext: String, mime: String): PendingFile {
        val stem = File(base, relStem)
        stem.parentFile?.mkdirs()
        val part = File(stem.parentFile, stem.name + ".part")
        val final = File(stem.parentFile, stem.name + ext)
        val stream = BufferedOutputStream(FileOutputStream(part))
        return object : PendingFile {
            override val stream: OutputStream = stream
            override fun commit(): String {
                stream.close()
                if (!part.renameTo(final)) {
                    part.copyTo(final, overwrite = true); part.delete()
                }
                return final.absolutePath
            }
            override fun abort() {
                try { stream.close() } catch (e: Exception) {}
                part.delete()
            }
        }
    }
}
