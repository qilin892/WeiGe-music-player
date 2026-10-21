package com.weige.music.script

import android.content.Context
import android.util.Log
import com.weige.music.data.KuwoApi
import com.weige.music.model.Quality
import com.weige.music.model.Song
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * 取流的唯一入口。
 *
 * 顺序：
 *   1. 用音源脚本（QuickJS 跑洛雪 JS），脚本优先 —— 这样上游更新了新源、修了新接口，我们能跟着走
 *   2. 前 3 个脚本都没给出可用直链时，退回内置的直链模板
 *
 * 整体限时 25 秒：宁可退回模板先让歌响起来，也不让界面一直转圈。
 * 连续失败会累计计数，达到阈值就让 [ScriptRepository] 强制刷新一次脚本。
 */
object MusicSource {

    private const val TAG = "MusicSource"
    private const val MAX_SCRIPT_TRIES = 2
    private const val REFRESH_AFTER_FAILS = 3
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"

    private val httpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    /** 整条链路（拉清单 + 下脚本 + 跑 JS + 取流）的总预算 */
    private const val TOTAL_BUDGET_MS = 22_000L

    /** 同一首歌的直链缓存：内存 + 磁盘持久化，跨进程跨天都能命中 */
    private const val URL_TTL_MS = 2 * 60 * 60 * 1000L

    private var appContext: Context? = null
    private var repo: ScriptRepository? = null
    private var engine: UserApiEngine? = null
    private var loadedScriptId: String? = null

    private var consecutiveFail = 0
    private val mutex = Mutex()

    private val memoryCache = LinkedHashMap<String, Pair<String, Long>>()
    private val prefs by lazy { appContext?.getSharedPreferences("url_cache", 0) }

    private fun cacheKey(song: Song, quality: Quality) = "${song.id}|${quality.level}"

    /** 先查内存再查磁盘。洛雪就是靠这层缓存做到「再点同一首歌秒开」的 */
    private fun cacheGet(key: String): String? {
        memoryCache[key]?.let { (u, exp) ->
            if (exp > System.currentTimeMillis()) return u
            memoryCache.remove(key)
        }
        val raw = prefs?.getString(key, null) ?: return null
        val sep = raw.indexOf('|')
        if (sep <= 0) return null
        val exp = raw.substring(0, sep).toLongOrNull() ?: return null
        if (exp < System.currentTimeMillis()) {
            prefs?.edit()?.remove(key)?.apply()
            return null
        }
        val url = raw.substring(sep + 1)
        memoryCache[key] = url to exp
        return url
    }

    private fun cachePut(key: String, url: String) {
        val exp = System.currentTimeMillis() + URL_TTL_MS
        if (memoryCache.size > 60) memoryCache.clear()
        memoryCache[key] = url to exp
        prefs?.edit()?.putString(key, "$exp|$url")?.apply()
    }

    /** 播放失败时作废这条缓存，下次重新解析 */
    fun invalidate(song: Song, quality: Quality) {
        val key = cacheKey(song, quality)
        memoryCache.remove(key)
        prefs?.edit()?.remove(key)?.apply()
        Log.i(TAG, "已作废直链缓存：$key")
    }

    /**
     * 验一下这个直链是不是真能播。
     *
     * 必须验：脚本返回「http 开头的字符串」不代表能用 —— 实测 changqing 给的
     * yinyue.haitangw.net 是 404，qdy 给的是 410，直接塞给播放器就是「歌放不了」。
     * 这里拉前 1KB 看状态码和文件头，几百毫秒，但能避免放到一半才发现是死链。
     */
    private fun checkPlayable(url: String): Boolean = runCatching {
        val req = okhttp3.Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Range", "bytes=0-1023")
            .build()
        httpClient.newCall(req).execute().use { r ->
            if (r.code != 200 && r.code != 206) {
                Log.w(TAG, "直链状态异常 ${r.code}：${url.take(60)}")
                return@use false
            }
            val b = r.body?.bytes() ?: return@use false
            if (b.size < 8) return@use false
            val head = String(b, 0, 4, Charsets.ISO_8859_1)
            val ok = b[0] == 0xFF.toByte() ||
                head.startsWith("ID3") ||
                head.startsWith("fLaC") ||
                head.startsWith("OggS") ||
                head.startsWith("RIFF")
            if (!ok) Log.w(TAG, "直链不是音频（${head.trim()}）：${url.take(60)}")
            ok
        }
    }.getOrElse {
        Log.w(TAG, "直链探活失败：${it.message}")
        false
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        repo = ScriptRepository(context.applicationContext)
    }

    /** 上一次脚本清单的更新时间，用于设置页展示 */
    fun lastUpdateAt(): Long = repo?.lastUpdatedAt() ?: 0L

    /** 本地已缓存的脚本数量。为 0 基本就说明下载一直没成功 */
    fun scriptCount(): Int = repo?.cached()?.size ?: 0

    /** 当前是否有可用脚本（用于界面提示） */
    fun hasScripts(): Boolean = scriptCount() > 0

    /**
     * 启动预热：把清单/脚本备好，并**提前把 JS 引擎跑起来**。
     *
     * 洛雪在 App 启动时就 setUserApi 加载脚本，所以点歌时脚本已就绪。
     * 我们之前是首次点歌才 evaluate 那几十 KB 混淆 JS，白白多花 1–2 秒。
     */
    suspend fun warmUp() {
        val ctx = appContext ?: return
        mutex.withLock {
            val repository = repo ?: return
            val scripts = runCatching { repository.ensure(false) }.getOrNull().orEmpty()
            if (scripts.isEmpty()) return

            val first = scripts.first()
            if (loadedScriptId == first.id && engine != null) return

            engine?.destroy()
            engine = null
            loadedScriptId = null

            val e = UserApiEngine(ctx)
            val sources = runCatching {
                e.load(
                    first.readText(),
                    ScriptMeta(first.id, first.name, version = first.version, author = first.author, homepage = first.homepage),
                )
            }.getOrNull()

            if (sources != null && sources.has("kw")) {
                engine = e
                loadedScriptId = first.id
                Log.i(TAG, "预热完成：${first.name}")
            } else {
                e.destroy()
            }
        }
    }

    suspend fun url(song: Song, quality: Quality): String? =
        withTimeoutOrNull(TOTAL_BUDGET_MS) {
            withContext(kotlinx.coroutines.Dispatchers.IO) { resolve(song, quality) }
        } ?: run {
            Log.w(TAG, "取流总超时 ${TOTAL_BUDGET_MS}ms，退回内置模板")
            KuwoApi.fallbackStreamUrl(song, quality)
        }

    private suspend fun resolve(song: Song, quality: Quality): String? = mutex.withLock {
        val ctx = appContext ?: return@withLock null
        val repository = repo ?: return@withLock null

        // 缓存优先：命中就直接返回，一行网络请求都不发
        val key = cacheKey(song, quality)
        cacheGet(key)?.let {
            Log.i(TAG, "命中直链缓存：$key")
            return@withLock it
        }

        val scriptQuality = when (quality) {
            Quality.STANDARD -> "128k"
            Quality.HIGH -> "320k"
            Quality.LOSSLESS -> "flac"
        }

        // 各脚本对字段要求不一样，常用的几个都给上，缺的它自己会忽略
        val musicInfo = JSONObject().apply {
            put("songmid", song.id)
            put("hash", song.id)
            put("rid", song.id)
            put("name", song.title)
            put("singer", song.artist)
            put("albumName", song.album)
            put("interval", song.durationText)
        }

        val force = consecutiveFail >= REFRESH_AFTER_FAILS

        // 有缓存就直接用，别每次点歌都去嗅一遍网络
        var scripts = repository.cached()
        if (scripts.isEmpty() || force) {
            scripts = runCatching { repository.ensure(force = force) }.getOrElse { emptyList() }
        }
        if (force) {
            Log.i(TAG, "连续失败 $consecutiveFail 次，已强制刷新脚本清单")
            consecutiveFail = 0
        }
        if (scripts.isEmpty()) {
            Log.w(TAG, "本地没有可用脚本，直接用内置模板")
            return@withLock KuwoApi.fallbackStreamUrl(song, quality)
        }

        for (script in scripts.take(MAX_SCRIPT_TRIES)) {
            if (loadedScriptId != script.id) {
                engine?.destroy()
                engine = null
                loadedScriptId = null

                val e = UserApiEngine(ctx)
                val sources = runCatching {
                    e.load(
                        script.readText(),
                        ScriptMeta(
                            id = script.id,
                            name = script.name,
                            version = script.version,
                            author = script.author,
                            homepage = script.homepage,
                        )
                    )
                }.getOrNull()

                if (sources == null || !sources.has("kw")) {
                    Log.w(TAG, "脚本 ${script.id} 加载失败或不支持酷我")
                    e.destroy()
                    continue
                }
                engine = e
                loadedScriptId = script.id
                Log.i(TAG, "已启用音源脚本：${script.name}")
            }

            val url = runCatching { engine?.getUrl("kw", musicInfo, scriptQuality) }.getOrNull()
            // 不只是「拿到了 http 字符串」就算成功，得真拉一下确认是音频
            if (!url.isNullOrBlank() && checkPlayable(url)) {
                consecutiveFail = 0
                cachePut(key, url)
                return@withLock url
            }
            if (!url.isNullOrBlank()) {
                Log.w(TAG, "脚本 ${script.id} 给的直链不可播，换下一个")
            }

            // 这个脚本取不到，换下一个
            Log.w(TAG, "脚本 ${script.id} 没有给出可用直链，换下一个")
            engine?.destroy()
            engine = null
            loadedScriptId = null
        }

        consecutiveFail++
        Log.w(TAG, "脚本全部失败（累计 $consecutiveFail 次），改用内置直链模板")
        KuwoApi.fallbackStreamUrl(song, quality)
    }

    fun destroy() {
        engine?.destroy()
        engine = null
        loadedScriptId = null
    }
}
