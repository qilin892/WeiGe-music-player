package com.weige.music.data

import android.util.Log
import com.weige.music.model.OnlinePlaylist
import com.weige.music.model.PlaylistDetail
import com.weige.music.model.PlaylistPage
import com.weige.music.model.PlaylistTag
import com.weige.music.model.PlaylistTagGroup
import com.weige.music.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 在线歌单（酷我官方歌单接口）。
 *
 * 链路和洛雪一致：
 *   标签体系 getTagList  →  歌单列表（推荐 / 按标签）  →  歌单详情 pl.svc
 *
 * 有个坑必须记住：tagId 不是纯数字，路由看标签自己的 `digest`。
 *   digest = "43"  →  mobileinterfaces.kuwo.cn/er.s（「专区」类，返回按 label 分组、不分页）
 *   其它 digest    →  getTagPlayList?id=<tagId>（正常分页）
 * 拿纯 id 去请求 getTagPlayList，专区类标签会返回 0 个歌单，看着像接口挂了。
 */
object PlaylistApi {

    private const val TAG = "PlaylistApi"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
    private const val BASE = "loginUid=0&loginSid=0&appUid=76039576"

    private const val TAGS_URL =
        "http://wapi.kuwo.cn/api/pc/classify/playlist/getTagList?cmd=rcm_keyword_playlist&user=0" +
            "&prod=kwplayer_pc_9.0.5.0&vipver=9.0.5.0&source=kwplayer_pc_9.0.5.0&$BASE"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private fun get(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Referer", "http://www.kuwo.cn/")
            .build()
        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw IllegalStateException("HTTP ${res.code}")
            return res.body?.string().orEmpty()
        }
    }

    /* ---------------------------------------------------------- 标签体系 */

    suspend fun tags(): List<PlaylistTagGroup> = withContext(Dispatchers.IO) {
        try {
            // 注意：外层是 {code, data:[…]}，这里的 data 直接就是分组数组
            val root = JSONObject(get(TAGS_URL))
            val arr = root.optJSONArray("data") ?: return@withContext emptyList()
            val out = ArrayList<PlaylistTagGroup>(arr.length())
            for (gi in 0 until arr.length()) {
                val g = arr.optJSONObject(gi) ?: continue
                val tags = ArrayList<PlaylistTag>()
                val list = g.optJSONArray("data") ?: continue
                for (i in 0 until list.length()) {
                    val t = list.optJSONObject(i) ?: continue
                    val id = t.optString("id")
                    if (id.isBlank()) continue
                    tags += PlaylistTag(
                        id = id,
                        name = t.optString("name").trim(),
                        digest = t.optString("digest").ifBlank { "8" },
                    )
                }
                if (tags.isNotEmpty()) {
                    out += PlaylistTagGroup(g.optString("name").trim(), tags)
                }
            }
            out
        } catch (e: Exception) {
            Log.w(TAG, "标签拉取失败：${e.message}")
            emptyList()
        }
    }

    /* ---------------------------------------------------------- 歌单列表 */

    /** 推荐歌单（不指定标签时）。total 实测 1700+，够一直翻 */
    suspend fun recommend(page: Int, size: Int = 30): PlaylistPage = withContext(Dispatchers.IO) {
        val url = "http://wapi.kuwo.cn/api/pc/classify/playlist/getRcmPlayList?" +
            "$BASE&pn=$page&rn=$size&order=hot"
        parsePage(runCatching { get(url) }.getOrDefault(""), page, size)
    }

    suspend fun byTag(tag: PlaylistTag, page: Int, size: Int = 30): PlaylistPage =
        withContext(Dispatchers.IO) {
            if (tag.digest == "43") {
                // 专区类：一次返回全部，项里只有 id/name/img/desc
                val url = "http://mobileinterfaces.kuwo.cn/er.s" +
                    "?type=get_pc_qz_data&f=web&id=${tag.id}&prod=pc"
                parseGroups(runCatching { get(url) }.getOrDefault(""))
            } else {
                val url = "http://wapi.kuwo.cn/api/pc/classify/playlist/getTagPlayList?" +
                    "$BASE&pn=$page&id=${tag.id}&rn=$size"
                parsePage(runCatching { get(url) }.getOrDefault(""), page, size)
            }
        }

    /** 标准分页结构：{code, data:{total, data:[…]}} */
    private fun parsePage(body: String, page: Int, size: Int): PlaylistPage {
        if (body.isBlank()) return PlaylistPage(emptyList(), false)
        val root = runCatching { JSONObject(body) }.getOrNull()
            ?: return PlaylistPage(emptyList(), false)
        if (root.optInt("code", 200) != 200) {
            Log.w(TAG, "列表接口 code=${root.optInt("code")}")
            return PlaylistPage(emptyList(), false)
        }
        val data = root.optJSONObject("data") ?: return PlaylistPage(emptyList(), false)
        val arr = data.optJSONArray("data") ?: return PlaylistPage(emptyList(), false)

        val list = ArrayList<OnlinePlaylist>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            parseItem(o)?.let { list += it }
        }
        val total = data.optString("total").toIntOrNull() ?: 0
        val hasMore = if (total > 0) page * size < total else list.size >= size
        return PlaylistPage(list, hasMore)
    }

    /** er.s 结构：[{label, list:[…]}]，无分页 */
    private fun parseGroups(body: String): PlaylistPage {
        if (body.isBlank()) return PlaylistPage(emptyList(), false)
        val arr = runCatching { JSONArray(body) }.getOrNull()
            ?: return PlaylistPage(emptyList(), false)
        val list = ArrayList<OnlinePlaylist>()
        for (gi in 0 until arr.length()) {
            val g = arr.optJSONObject(gi) ?: continue
            val items = g.optJSONArray("list") ?: continue
            for (i in 0 until items.length()) {
                val o = items.optJSONObject(i) ?: continue
                // digest=13 是专辑，不是歌单：pl.svc 拿不到歌，点进去只会空白，不展示
                if (o.optString("digest") == "13") continue
                parseItem(o)?.let { list += it }
            }
        }
        return PlaylistPage(list, false)
    }

    private fun parseItem(o: JSONObject): OnlinePlaylist? {
        val id = o.optString("id").trim()
        if (id.isBlank()) return null
        val name = o.optString("name").trim()
        if (name.isBlank()) return null
        return OnlinePlaylist(
            id = id,
            name = name,
            cover = coverUrl(o.optString("img")),
            songCount = o.optString("total").toIntOrNull() ?: 0,
            playCount = formatCount(o.optString("listencnt").toLongOrNull() ?: 0L),
            author = o.optString("uname").trim(),
            desc = o.optString("desc").trim(),
            digest = o.optString("digest").ifBlank { "8" },
        )
    }

    /* ---------------------------------------------------------- 歌单详情 */

    /**
     * 歌单详情。
     *
     * 有一批歌单是「空壳」：专区里的「经典国语 / 经典粤语 / 经典英文」，
     * 服务端 musicCount 本来就是 0 —— pl.svc 返回 `result:ok` 但 musiclist 是空数组，
     * 标题也是空串。点进去必然一片空白，不是我们解析的问题。
     *
     * 处理办法：拿歌单名再搜一次同类歌单，用第一个真有歌的顶上，
     * 这样点「经典国语」至少能看到一个真的国语经典歌单。
     */
    suspend fun detail(id: String, fallbackName: String = ""): PlaylistDetail =
        withContext(Dispatchers.IO) {
            val direct = runCatching { detailByPid(id) }.getOrNull()
            if (direct != null && direct.songs.isNotEmpty()) return@withContext direct

            if (fallbackName.isNotBlank()) {
                val candidates = runCatching { searchPlaylist(fallbackName) }
                    .getOrDefault(emptyList())
                for (c in candidates.take(4)) {
                    if (c.id == id) continue
                    val d = runCatching { detailByPid(c.id) }.getOrNull() ?: continue
                    if (d.songs.isNotEmpty()) {
                        Log.i(TAG, "空歌单 $id($fallbackName) → 换成 ${d.info.id}(${d.info.name})")
                        return@withContext d
                    }
                }
            }

            direct ?: PlaylistDetail(
                OnlinePlaylist(id = id, name = fallbackName, cover = ""),
                emptyList(),
            )
        }

    private fun detailByPid(id: String): PlaylistDetail {
        val url = "http://nplserver.kuwo.cn/pl.svc?op=getlistinfo&pid=$id&pn=0&rn=1000" +
            "&encode=utf8&keyset=pl2012&identity=kuwo&pcmp4=1" +
            "&vipver=MUSIC_9.0.5.0_W1&newver=1"
        val body = get(url)
        val root = JSONObject(body)
        if (root.optString("result") != "ok") {
            Log.w(TAG, "详情接口失败：${root.optString("result")}")
            return PlaylistDetail(
                OnlinePlaylist(id = id, name = "", cover = ""),
                emptyList(),
            )
        }

        val arr = root.optJSONArray("musiclist") ?: JSONArray()
        val songs = ArrayList<Song>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val rid = o.optString("id").trim()
            if (rid.isBlank()) continue
            songs += Song(
                id = rid,
                title = o.optString("name").trim(),
                artist = o.optString("artist").trim(),
                album = o.optString("album").trim(),
                durationSec = o.optString("duration").toIntOrNull() ?: 0,
                // 详情里的 albumpic 是完整 URL，转回短路径，好让 KuwoApi.coverUrl 统一换尺寸
                coverPath = shortCover(o.optString("albumpic")),
            )
        }

        val info = OnlinePlaylist(
            id = id,
            name = root.optString("title").trim(),
            cover = coverUrl(root.optString("pic")),
            songCount = songs.size,
            playCount = formatCount(root.optString("playnum").toLongOrNull() ?: 0L),
            author = root.optString("uname").trim(),
            desc = root.optString("info").trim(),
        )
        return PlaylistDetail(info, songs)
    }

    /**
     * 按名字搜歌单，用于给「空壳歌单」找替代。
     *
     * 这个接口返回的是**单引号伪 JSON**（`{'name':'…'}`），不能直接扔给 JSONObject，
     * 得先把结构位置的引号换成双引号。
     */
    private fun searchPlaylist(kw: String, limit: Int = 8): List<OnlinePlaylist> {
        val q = java.net.URLEncoder.encode(kw, "UTF-8")
        val url = "http://search.kuwo.cn/r.s?all=$q&pn=0&rn=$limit&rformat=json&encoding=utf8" +
            "&ver=mbox&vipver=MUSIC_8.7.7.0_BCS37&plat=pc&devid=28156413&ft=playlist&pay=0"
        val root = runCatching { JSONObject(looseJson(get(url))) }.getOrNull()
            ?: return emptyList()
        val arr = root.optJSONArray("abslist") ?: return emptyList()

        val out = ArrayList<OnlinePlaylist>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("DC_TARGETTYPE") != "songlist") continue
            val pid = o.optString("DC_TARGETID").trim()
            val n = o.optString("name").trim()
            if (pid.isBlank() || n.isBlank()) continue
            out += OnlinePlaylist(id = pid, name = n, cover = coverUrl(o.optString("hts_pic")))
        }
        return out
    }

    /* ---------------------------------------------------------- 工具 */

    /**
     * 单引号伪 JSON → 标准 JSON。
     *
     * 只替换**结构位置**的引号（前面是 `{ : , [`，或后面是 `: , ] }`），
     * 内容里的撇号（`Don't`）保持原样，否则会把 JSON 弄坏。
     */
    private val PSEUDO_QUOTE = Regex("""(?<=[{:,\[])(')|(')(?=\s*[:,\]}])""")

    private fun looseJson(s: String): String = PSEUDO_QUOTE.replace(s.trim(), "\"")

    /** 播放量：106775531 → 10677.5万 */
    private fun formatCount(num: Long): String = when {
        num > 100_000_000 -> "%.1f亿".format(num / 10_000_000.0)
        num > 10_000 -> "%.1f万".format(num / 10_000.0)
        num > 0 -> num.toString()
        else -> ""
    }

    /**
     * 封面统一成 500 宽。
     * 两种来源：专辑封面（带 /star/albumcover/ 短路径，可改尺寸目录）
     * 和用户歌单封面（kwcdn 直链）。
     *
     * 注意 kwcdn 这个 CDN **不支持 https**（连不上、curl 返回 000），
     * 必须换成同源的 `imgN.kuwo.cn`（实测同图、字节完全一致）再走 https，
     * 否则一半歌单封面是空白。
     */
    private fun coverUrl(raw: String): String {
        val u0 = raw.trim()
        if (u0.isEmpty()) return ""
        val key = "/star/albumcover/"
        val i = u0.indexOf(key)
        if (i >= 0) {
            val p = u0.substring(i + key.length)
            val parts = p.split('/')
            val path = if (parts.size >= 2 && parts[0].all { it.isDigit() }) {
                (listOf("500") + parts.drop(1)).joinToString("/")
            } else {
                p
            }
            return "https://img4.kuwo.cn/star/albumcover/$path"
        }
        val u = Regex("""https?://([a-z0-9]+)\.kwcdn\.kuwo\.cn/""")
            .replace(u0) { m -> "https://${m.groupValues[1]}.kuwo.cn/" }
        return u.replace("http://", "https://")
    }

    /** 完整封面 URL → 短路径（交给 KuwoApi.coverUrl 换尺寸） */
    private fun shortCover(url: String): String {
        val key = "/star/albumcover/"
        val i = url.indexOf(key)
        return if (i >= 0) url.substring(i + key.length) else ""
    }
}
