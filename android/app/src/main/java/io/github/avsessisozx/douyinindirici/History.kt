package io.github.avsessisozx.douyinindirici

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * İndirme geçmişi: hangi profilden hangi gönderilerin indiğini ve profilin daha önce sonuna kadar
 * taranıp taranmadığını tutar. Windows sürümündeki history.py ile aynı mantık.
 * Geçmiş Douyin hesabına değil indirilen profile (sec_uid) bağlıdır.
 */
interface HistoryStore {
    fun known(secUid: String): Set<String>
    fun mark(secUid: String, awemeId: String, author: String = "")
    fun count(): Int
    /** Bütün geçmişi siler (dosyalara dokunmaz); silinen gönderi kaydı sayısını döndürür. */
    fun clear(): Int
    fun wasScanned(secUid: String): Boolean
    fun setScanned(secUid: String, author: String = "")
}

/** Telefonda kalıcı SQLite dosyası (gecmis.sqlite3). */
class SqliteHistory(ctx: Context) : HistoryStore {
    private val helper = object : SQLiteOpenHelper(ctx.applicationContext, "gecmis.sqlite3", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE downloaded (sec_uid TEXT NOT NULL, aweme_id TEXT NOT NULL, author TEXT, at INTEGER NOT NULL, " +
                    "PRIMARY KEY (sec_uid, aweme_id))"
            )
            db.execSQL("CREATE TABLE scanned (sec_uid TEXT PRIMARY KEY, author TEXT, at INTEGER NOT NULL)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    }

    @Synchronized
    override fun known(secUid: String): Set<String> {
        if (secUid.isEmpty()) return emptySet()
        val out = HashSet<String>()
        helper.readableDatabase.rawQuery("SELECT aweme_id FROM downloaded WHERE sec_uid=?", arrayOf(secUid)).use { c ->
            while (c.moveToNext()) out += c.getString(0)
        }
        return out
    }

    @Synchronized
    override fun mark(secUid: String, awemeId: String, author: String) {
        if (secUid.isEmpty() || awemeId.isEmpty()) return
        val v = ContentValues().apply {
            put("sec_uid", secUid); put("aweme_id", awemeId); put("author", author)
            put("at", System.currentTimeMillis() / 1000)
        }
        helper.writableDatabase.insertWithOnConflict("downloaded", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    override fun count(): Int =
        helper.readableDatabase.rawQuery("SELECT COUNT(*) FROM downloaded", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    @Synchronized
    override fun clear(): Int {
        val db = helper.writableDatabase
        val n = db.rawQuery("SELECT COUNT(*) FROM downloaded", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        db.delete("downloaded", null, null)
        db.delete("scanned", null, null)
        return n
    }

    @Synchronized
    override fun wasScanned(secUid: String): Boolean {
        if (secUid.isEmpty()) return false
        return helper.readableDatabase.rawQuery("SELECT 1 FROM scanned WHERE sec_uid=?", arrayOf(secUid)).use { it.moveToFirst() }
    }

    @Synchronized
    override fun setScanned(secUid: String, author: String) {
        if (secUid.isEmpty()) return
        val v = ContentValues().apply {
            put("sec_uid", secUid); put("author", author); put("at", System.currentTimeMillis() / 1000)
        }
        helper.writableDatabase.insertWithOnConflict("scanned", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }
}

/** Bellekte tutan sürüm (birim testleri için; telefonun SQLite'ına gerek kalmaz). */
class MemoryHistory : HistoryStore {
    private val done = HashMap<String, MutableSet<String>>()
    private val scanned = HashSet<String>()

    @Synchronized override fun known(secUid: String): Set<String> = HashSet(done[secUid] ?: emptySet())
    @Synchronized override fun mark(secUid: String, awemeId: String, author: String) {
        if (secUid.isEmpty() || awemeId.isEmpty()) return
        done.getOrPut(secUid) { HashSet() } += awemeId
    }
    @Synchronized override fun count(): Int = done.values.sumOf { it.size }
    @Synchronized override fun clear(): Int { val n = count(); done.clear(); scanned.clear(); return n }
    @Synchronized override fun wasScanned(secUid: String): Boolean = secUid in scanned
    @Synchronized override fun setScanned(secUid: String, author: String) { if (secUid.isNotEmpty()) scanned += secUid }
}
