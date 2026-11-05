package io.github.avsessisozx.douyinindirici

import android.app.Application
import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Locale

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Core.init(this)
    }
}

/** Uygulama genelinde tek kopya: çeviriler, ayarlar, indirme motoru. */
object Core {
    lateinit var app: Context; private set
    lateinit var tr: Tr; private set
    lateinit var engine: Engine; private set
    /** İndirme geçmişi (gecmis.sqlite3). Testler TestHooks.history ile değiştirebilir. */
    val history: HistoryStore get() = TestHooks.history ?: sqlHistory
    private lateinit var sqlHistory: HistoryStore
    @Volatile var web: DouyinWeb? = null
    @Volatile var settings: SettingsValues = SettingsValues()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private const val PREFS = "ayarlar"

    @Synchronized
    fun init(ctx: Context) {
        if (::engine.isInitialized) return
        app = ctx.applicationContext
        val langs = (app.assets.list("ceviriler") ?: emptyArray())
            .filter { it.endsWith(".json") }.map { it.removeSuffix(".json") }.sorted()
            .ifEmpty { listOf(Tr.FALLBACK) }
        tr = Tr({ code ->
            try { app.assets.open("ceviriler/$code.json").bufferedReader(Charsets.UTF_8).use { it.readText() } } catch (e: Exception) { null }
        }, langs)
        settings = SettingsValues.fromJson(app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("json", null), langs)
        sqlHistory = SqliteHistory(app)
        engine = Engine(
            webProvider = { web },
            sinkFactory = { TestHooks.sinkFactory?.invoke() ?: MediaStoreSink(app) },
            scope = scope,
            historyProvider = { history },
            alertSound = { alertSound() },
        )
    }

    /** Doğrulama (bulmaca) çıkınca dikkat çekmek için kısa bir ses. */
    private fun alertSound() {
        val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
        tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 600)
        scope.launch {
            kotlinx.coroutines.delay(1000)
            tone.release()
        }
    }

    fun languages(): List<String> = tr.available().map { it.first }

    fun saveSettings(v: SettingsValues) {
        settings = v
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("json", v.toJson().toString()).apply()
    }

    fun lang(): String = tr.resolve(settings.lang, Locale.getDefault().language)

    fun text(key: String, vararg params: Pair<String, Any?>): String = tr.text(key, lang(), mapOf(*params))

    fun version(): String = try {
        app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: ""
    } catch (e: Exception) { "" }
}
