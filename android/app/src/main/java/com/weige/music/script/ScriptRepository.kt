package com.weige.music.script

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** 一份已经下载到本地的音源脚本 */
data class CachedScript(
    val id: String,
    val name: String,
    val file: File,
    val version: String,
    val author: String,
    val homepage: String,
) {
    fun readText(): String = file.readText()
}

/**
 * 音源脚本的获取与缓存。
 *
 * 清单来自 lx-music-source 的 README（一个 Markdown 列表，每节给两个链接，取 GitHub 原始地址那一条）。
 * 脚本本身是高度混淆的 JS，本地不做任何解析，原样存下来交给 [UserApiEngine] 执行。
 *
 * 下载源的选择很关键（实测数据）：
 *   cdn.jsdelivr.net  ≈ 0.7s/个      ← 主源
 *   raw.githubusercontent.com ≈ 0.6s/个（本机可达，但各网络环境差异大）
 *   ghproxy.net       ≈ 3s/个
 *   gh-proxy.org      ≈ 12–15s/个    ← 之前用的这个，12 个串行要 3 分钟，就是它把点歌卡死的
 *
 * 所以现在：主源 jsDelivr + raw 兜底、并发下载、整体限时。
 */
class ScriptRepository(private val context: Context) {

    companion object {
        private const val TAG = "ScriptRepo"
        private const val REPO = "pdone/lx-music-source"
        private const val BRANCH = "main"

        private const val INDEX_CDN = "https://cdn.jsdelivr.net/gh/$REPO@$BRANCH/README.md"
        private const val INDEX_RAW = "https://raw.githubusercontent.com/$REPO/$BRANCH/README.md"

        private const val TTL_MS = 24 * 60 * 60 * 1000L

        /** 刷新总时限：超过就先用手上有的 */
        private const val REFRESH_TIMEOUT_MS = 20_000L

        /** 并发数：再多容易被 CDN 限流 */
        private const val PARALLEL = 4

        /**
         * GitHub 加速站。按 README 里「其他加速站点」给的清单，
         * 格式统一成 `{proxy}https://raw...`（实测 ghproxy.net / gh-proxy.org 都认这个写法）。
         *
         * 各站不同时段可用性差别很大，所以不挑，全部并发去抢，谁先给出**合法脚本**用谁。
         */
        private val PROXIES = listOf(
            "https://gh-proxy.com/",
            "https://ghproxy.net/",
            "https://ghproxy.cn/",
            "https://gh-proxy.org/",
            "https://gh.llkk.cc/",
            "https://github.moeyy.xyz/",
            "https://ghp.ci/",
            "https://gh.api.99988866.xyz/",
        )

        /**
         * 取流优先级。实测（2026-10-21）11 个脚本里只有 xinghai 给的直链真能播：
         * 它走酷我官方 CDN（car-er.kuwo.cn），现在稳定；changqing 的 yinyue.haitangw.net 已 404，
         * qdy 返回 410，ikun/lx 直接 fetch failed，sixyin 要求更新脚本。
         */
        private val PREFERRED = listOf("xinghai", "changqing", "qdy", "huanyin")
    }

    private val dir = File(context.filesDir, "sources")
    private val indexFile = File(dir, "index.json")

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    private fun http(url: String): String? = runCatching {
        val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return@use null
            r.body?.string()
        }
    }.getOrElse {
        Log.w(TAG, "fetch failed ${url.take(60)}: ${it.message}")
        null
    }

    /** 依次尝试多个源，谁先返回用谁 */
    private fun httpAny(urls: List<String>): String? {
        for (u in urls) {
            http(u)?.let { if (it.length > 512) return it }
        }
        return null
    }

    /**
     * 多源竞速：同时打几个源，第一个拿到**合法内容**的返回。
     *
     * 串行重试在移动网络下体验很差 —— 各站可用性随时变，串着等要等好几轮。
     * 并发出去，最慢也就一个超时的时间。
     *
     * valid 用来排除「200 但内容是错误页」的情况：加速站经常回一个 HTML 错误页，
     * 大小还不小，只校验长度会被骗过去。
     */
    private suspend fun httpRace(
        urls: List<String>,
        timeoutMs: Long = 9000,
        valid: (String) -> Boolean,
    ): String? = coroutineScope {
        if (urls.isEmpty()) return@coroutineScope null
        val winner = CompletableDeferred<String>()
        val lock = Any()
        var pending = urls.size

        urls.forEach { u ->
            launch(Dispatchers.IO) {
                val body = http(u)
                if (body != null && valid(body)) {
                    winner.complete(body)
                } else {
                    synchronized(lock) {
                        pending--
                        if (pending == 0) winner.complete("")
                    }
                }
            }
        }

        withTimeoutOrNull(timeoutMs) { winner.await() }?.takeIf { it.isNotEmpty() }
    }

    /** 脚本一定带 @name 注释头；错误页没有 */
    private fun isScript(body: String): Boolean = body.length > 2048 && body.contains("@name")

    /* ------------------------------------------------------------- 对外 */

    /** 返回可用的脚本列表（按优先级排好）。force=true 时忽略缓存强制刷新。 */
    suspend fun ensure(force: Boolean = false): List<CachedScript> = withContext(Dispatchers.IO) {
        // 首次启动先把 APK 里内置的那份铺开：手机网络不通 GitHub 时也能立刻用
        if (scan().isEmpty()) seedFromAssets()

        val cached = scan()
        if (!force && cached.isNotEmpty() && !expired()) {
            return@withContext order(cached)
        }

        val refreshed = refresh()
        if (refreshed.isNotEmpty()) order(refreshed) else order(cached)
    }

    /**
     * 把 assets/sources/ 里预置的脚本复制到缓存目录。
     *
     * 这些脚本是打包时的快照（来自同一个仓库），作用只有一个：**保证首次启动就有源可用**。
     * 之后只要网络能通，[refresh] 会覆盖成线上最新版。
     */
    private fun seedFromAssets(): Int {
        val names = runCatching { context.assets.list("sources") }.getOrNull() ?: return 0
        if (names.isEmpty()) return 0

        dir.mkdirs()
        var copied = 0
        for (name in names) {
            if (!name.endsWith(".js")) continue
            runCatching {
                context.assets.open("sources/$name").use { input ->
                    File(dir, name).outputStream().use { output -> input.copyTo(output) }
                }
                copied++
            }.onFailure { Log.w(TAG, "内置脚本 $name 复制失败：${it.message}") }
        }
        if (copied > 0) {
            // 写一份索引，把「上次更新时间」定在现在：
            // 否则 expired() 会立刻为真，首次点歌又要去等一轮网络超时。
            writeIndex(scan())
            Log.i(TAG, "已铺开 $copied 个内置脚本")
        }
        return copied
    }

    /** 只读本地，不发网络请求 */
    fun cached(): List<CachedScript> = order(scan())

    fun lastUpdatedAt(): Long = runCatching {
        JSONObject(indexFile.readText()).optLong("updatedAt")
    }.getOrDefault(0L)

    /* ------------------------------------------------------------- 内部 */

    private fun expired(): Boolean = System.currentTimeMillis() - lastUpdatedAt() > TTL_MS

    private suspend fun refresh(): List<CachedScript> = withContext(Dispatchers.IO) {
        withTimeoutOrNull(REFRESH_TIMEOUT_MS) {
            // 清单也走竞速：CDN + raw + 各加速站
            val indexUrls = buildList {
                add(INDEX_CDN)
                add(INDEX_RAW)
                PROXIES.forEach { add(it + INDEX_RAW) }
            }
            val readme = httpRace(indexUrls, 9000) { it.length > 512 && it.contains("###") }
            if (readme == null) {
                Log.w(TAG, "清单拉取失败（所有源都慢/不通），继续用本地缓存")
                return@withTimeoutOrNull emptyList()
            }

            val targets = parseScriptUrls(readme)
            if (targets.isEmpty()) {
                Log.w(TAG, "清单里没解析到脚本地址")
                return@withTimeoutOrNull emptyList()
            }
            Log.i(TAG, "清单解析到 ${targets.size} 个脚本，开始并发下载")

            dir.mkdirs()
            val sem = Semaphore(PARALLEL)
            val saved = coroutineScope {
                targets.map { (id, url) ->
                    async(Dispatchers.IO) {
                        sem.withPermit { downloadOne(id, url) }
                    }
                }.awaitAll()
            }.filterNotNull()

            if (saved.isNotEmpty()) {
                writeIndex(saved)
                Log.i(TAG, "刷新完成，缓存 ${saved.size}/${targets.size} 个脚本")
            }
            saved
        } ?: run {
            Log.w(TAG, "刷新超时（${REFRESH_TIMEOUT_MS}ms），先用已有缓存")
            emptyList()
        }
    }

    private suspend fun downloadOne(id: String, rawUrl: String): CachedScript? {
        // jsDelivr（一般最快）+ raw 直连 + 全部加速站，一起抢
        val candidates = buildList {
            toJsdelivr(rawUrl)?.let { add(it) }
            add(rawUrl)
            PROXIES.forEach { add(it + rawUrl) }
        }

        val body = httpRace(candidates, 8000, ::isScript) ?: run {
            Log.w(TAG, "脚本 $id 所有源都没拿到有效内容")
            return null
        }

        val file = File(dir, "$id.js")
        if (runCatching { file.writeText(body) }.isFailure) return null

        val meta = parseMeta(body, id)
        return CachedScript(id, meta.name, file, meta.version, meta.author, meta.homepage)
    }

    private fun writeIndex(saved: List<CachedScript>) {
        val arr = JSONArray()
        saved.forEach {
            arr.put(
                JSONObject().apply {
                    put("id", it.id)
                    put("name", it.name)
                    put("version", it.version)
                }
            )
        }
        runCatching {
            indexFile.writeText(
                JSONObject().apply {
                    put("updatedAt", System.currentTimeMillis())
                    put("scripts", arr)
                }.toString()
            )
        }
    }

    /** raw.githubusercontent.com/... → cdn.jsdelivr.net/gh/...，静态资源走 CDN 快一个数量级 */
    private fun toJsdelivr(raw: String): String? {
        val m = Regex("""^https://raw\.githubusercontent\.com/([^/]+)/([^/]+)/([^/]+)/(.+)$""").find(raw)
            ?: return null
        val (owner, repo, branch, path) = m.destructured
        return "https://cdn.jsdelivr.net/gh/$owner/$repo@$branch/$path"
    }

    /** 解析 README：每节标题下面的第一个 curl 地址就是 GitHub 原始链接 */
    private fun parseScriptUrls(readme: String): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        val section = Regex("""^###\s+(.+)$""", RegexOption.MULTILINE)
        val scriptUrl = Regex("""https://raw\.githubusercontent\.com/\S+?/([a-zA-Z0-9_-]+)/latest\.js""")

        val headers = section.findAll(readme).toList()
        for ((i, h) in headers.withIndex()) {
            val title = h.groupValues[1].trim()
            if (title.contains("加速")) continue

            val start = h.range.last + 1
            val end = if (i + 1 < headers.size) headers[i + 1].range.first else readme.length
            val block = readme.substring(start, end.coerceAtMost(readme.length))

            val m = scriptUrl.find(block) ?: continue
            result += m.groupValues[1] to m.value
        }
        return result
    }

    private fun parseMeta(script: String, fallbackId: String): Meta {
        fun field(key: String): String =
            Regex("""@$key\s+(.+)""").find(script)?.groupValues?.get(1)?.trim().orEmpty()

        return Meta(
            name = field("name").ifEmpty { fallbackId },
            version = field("version"),
            author = field("author"),
            homepage = field("homepage"),
        )
    }

    private fun scan(): List<CachedScript> {
        if (!dir.exists()) return emptyList()
        return dir.listFiles { f -> f.extension == "js" }?.mapNotNull { f ->
            val id = f.nameWithoutExtension
            val text = runCatching { f.readText() }.getOrNull() ?: return@mapNotNull null
            val meta = parseMeta(text, id)
            CachedScript(id, meta.name, f, meta.version, meta.author, meta.homepage)
        } ?: emptyList()
    }

    private fun order(list: List<CachedScript>): List<CachedScript> {
        val rank = { s: CachedScript -> PREFERRED.indexOf(s.id).let { if (it < 0) PREFERRED.size else it } }
        return list.sortedBy(rank)
    }

    private data class Meta(
        val name: String,
        val version: String,
        val author: String,
        val homepage: String,
    )
}
