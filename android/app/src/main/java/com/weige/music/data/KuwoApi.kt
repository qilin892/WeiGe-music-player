package com.weige.music.data

import com.weige.music.model.LyricLine
import com.weige.music.model.Quality
import com.weige.music.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 酷我音乐接口。
 *
 * 三个来源都是公开 HTTP 接口，不依赖任何聚合服务：
 *   搜索   search.kuwo.cn/r.s            —— 客户端版参数，能拿到真原版（web 版大量返回翻唱/伴奏）
 *   直链   musicapi.haitangw.net         —— 按 rid + level 拼 URL，直接回音频流
 *   歌词   m.kuwo.cn/newh5/...           —— 必须带完整桌面 UA，简写 UA 会被判成非法客户端
 *
 * 注意：搜索接口是明文 http，Android 9+ 需要 usesCleartextTraffic（已在 Manifest 声明）。
 */
object KuwoApi {

    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"

    private const val CLIENT_PARAMS =
        "client=kt&uid=794762570&ver=kwplayer_ar_9.2.2.1&vipver=1" +
            "&show_copyright_off=1&newver=1&ft=music&cluster=0&strategy=2012" +
            "&encoding=utf8&rformat=json&vermerge=1&mobi=1&issubtitle=1"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private fun get(url: String, referer: String? = null): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .apply { referer?.let { header("Referer", it) } }
            .build()
        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw IllegalStateException("HTTP ${res.code}")
            return res.body?.string().orEmpty()
        }
    }

    /** 搜索。结果按「原版优先」排序，DJ/伴奏/翻唱等版本排到后面。 */
    suspend fun search(keyword: String, page: Int = 0, size: Int = 30): List<Song> =
        withContext(Dispatchers.IO) {
            val kw = keyword.trim()
            if (kw.isEmpty()) return@withContext emptyList()

            val url = "http://search.kuwo.cn/r.s?$CLIENT_PARAMS" +
                "&all=${java.net.URLEncoder.encode(kw, "UTF-8")}" +
                "&pn=$page&rn=$size&primitive=0&devid=794762570"

            val body = get(url, "http://www.kuwo.cn/")
            val root = JSONObject(body)
            val arr = root.optJSONArray("abslist") ?: return@withContext emptyList()

            val songs = ArrayList<Song>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("DC_TARGETID").ifBlank { o.optString("MUSICRID").removePrefix("MUSIC_") }
                if (id.isBlank()) continue
                songs += Song(
                    id = id,
                    title = o.optString("SONGNAME").ifBlank { o.optString("NAME") }.trim(),
                    artist = o.optString("ARTIST").trim(),
                    album = o.optString("ALBUM").trim(),
                    durationSec = o.optString("DURATION").toIntOrNull() ?: 0,
                    coverPath = o.optString("web_albumpic_short").trim(),
                )
            }

            // 原版在前，翻唱/DJ/伴奏在后
            songs.sortedBy { if (Song.isBadVersion(it)) 1 else 0 }
        }

    /**
     * 兜底直链模板。
     *
     * 正常情况下取流走 [com.weige.music.script.MusicSource]（音源脚本），
     * 只有在脚本全部失败时才用这条写死的模板 —— 它能保证「至少还有一条路」，
     * 但接口一旦改版就失效，所以不能当作主力。
     */
    fun fallbackStreamUrl(song: Song, quality: Quality): String =
        "https://musicapi.haitangw.net/music/kw.php?type=mp3" +
            "&id=${song.id}&level=${quality.level}"

    /**
     * 封面。
     *
     * 搜索返回的是 `120/22/98/xxx.jpg` 这种短路径，**首段就是尺寸目录**，
     * 把它换成 500 / 800 就能拿到同图的高清版（实测 120=4.7KB、500=54KB、800=132KB）。
     * 之前固定拼 120，播放页铺到 236dp 自然是糊的。
     */
    fun coverUrl(song: Song, size: Int = 500): String {
        val p = song.coverPath.trim().trimStart('/')
        if (p.isEmpty()) return ""
        val parts = p.split('/')
        val path = if (parts.size >= 2 && parts[0].all { it.isDigit() }) {
            (listOf(size.toString()) + parts.subList(1, parts.size)).joinToString("/")
        } else {
            p
        }
        return "https://img4.kuwo.cn/star/albumcover/$path"
    }

    /**
     * 歌词。酷我优先（音源和歌词同版本，时间轴最准），
     * 酷我缺词时走网易云兜底 —— 非独家版权的热门歌在酷我大量没有 LRC。
     */
    suspend fun lyric(song: Song): List<LyricLine> = withContext(Dispatchers.IO) {
        val local = runCatching { kuwoLyric(song) }.getOrDefault(emptyList())
        if (local.isNotEmpty()) return@withContext local
        runCatching { neteaseLyric(song) }.getOrDefault(emptyList())
    }

    private fun kuwoLyric(song: Song): List<LyricLine> {
        val body = get("http://m.kuwo.cn/newh5/singles/songinfoandlrc?musicId=${song.id}")
        val list = JSONObject(body).optJSONObject("data")?.optJSONArray("lrclist")
            ?: return emptyList()

        val lines = ArrayList<LyricLine>(list.length())
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            val t = o.optString("time").toFloatOrNull() ?: continue
            val text = o.optString("lineLyric").trim()
            if (text.isEmpty()) continue
            lines += LyricLine(t, text)
        }
        return lines.sortedBy { it.time }
    }

    /** 网易云：按「歌名 歌手」找 id，再取 LRC 文本 */
    private fun neteaseLyric(song: Song): List<LyricLine> {
        val kw = java.net.URLEncoder.encode("${song.title} ${song.artist}".trim(), "UTF-8")
        val searchBody = get(
            "https://music.163.com/api/search/get?s=$kw&type=1&limit=5&offset=0",
            "https://music.163.com/",
        )
        val arr = JSONObject(searchBody).optJSONObject("result")?.optJSONArray("songs")
            ?: return emptyList()

        val want = normalize(song.title)
        var id = 0L
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val got = normalize(o.optString("name"))
            // 名称对不上就跳过，别把同名翻唱的歌词套到原版上
            if (want.isNotEmpty() && got.isNotEmpty() && !got.contains(want) && !want.contains(got)) continue
            id = o.optLong("id")
            if (id > 0) break
        }
        if (id <= 0) return emptyList()

        val body = get(
            "https://music.163.com/api/song/lyric?id=$id&lv=1&kv=1&tv=-1",
            "https://music.163.com/",
        )
        return parseLrc(JSONObject(body).optJSONObject("lrc")?.optString("lyric").orEmpty())
    }

    /** 去掉括号内容与空白、连字符，只留可比较的主干 */
    private fun normalize(s: String): String = s
        .replace(Regex("[（(\\[【].*?[)）\\]】]"), "")
        .replace(Regex("[\\s\\u3000·・\\-—_/]"), "")
        .lowercase()

    private val LRC_TAG = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

    /**
     * LRC 开头常混着制作人员名单，这些不该当歌词滚动。
     *
     * 网易云的写法很杂：「作词 : 唐恬」「人声录音 : 利伟明」「词版权管理方：…」
     * 所以单字用精确匹配、其它用包含匹配，比逐个枚举靠谱。
     */
    private val META_EXACT = setOf("词", "曲", "op", "sp")

    private val META_FUZZY = listOf(
        "作词", "作曲", "编曲", "制作", "监制", "混音", "录音", "母带", "和声", "和音",
        "统筹", "发行", "出品", "改编", "企划", "文案", "版权", "演奏", "演唱", "配唱",
        "人声", "美术", "视觉", "鸣谢", "支持", "词曲", "录音室", "设计", "出品人",
        "后期", "工程", "工作室", "唱片", "传媒", "文化", "音乐总监",
        // 乐器声部：LRC 末尾常跟一长串演奏者名单
        "小提琴", "大提琴", "中提琴", "提琴", "钢琴", "长笛", "萨克斯", "二胡", "古筝",
        "琵琶", "口琴", "手风琴", "小号", "长号", "圆号", "单簧管", "双簧管", "竖琴",
        "尤克里里", "曼陀林", "吉他", "贝斯", "鼓", "打击乐", "弦乐", "键盘", "合成器",
        "笛", "箫",
    )

    private fun isMetaLine(text: String): Boolean {
        val i = text.indexOfFirst { it == ':' || it == '：' }
        if (i <= 0 || i > 12) return false
        val head = text.substring(0, i).trim().lowercase()
        if (head.isEmpty()) return false
        if (head in META_EXACT) return true
        return META_FUZZY.any { head.contains(it) }
    }

    /** 解析 LRC。一行多时间戳会展开成多行 */
    private fun parseLrc(raw: String): List<LyricLine> {
        if (raw.isBlank()) return emptyList()
        val out = ArrayList<LyricLine>(64)
        raw.lineSequence().forEach { line ->
            val tags = LRC_TAG.findAll(line).toList()
            if (tags.isEmpty()) return@forEach
            val text = line.substring(tags.last().range.last + 1).trim()
            if (text.isEmpty()) return@forEach
            if (isMetaLine(text)) return@forEach
            for (tag in tags) {
                val m = tag.groupValues[1].toIntOrNull() ?: continue
                val s = tag.groupValues[2].toIntOrNull() ?: continue
                val f = tag.groupValues[3]
                val ms = when (f.length) {
                    0 -> 0
                    1 -> (f.toIntOrNull() ?: 0) * 100
                    2 -> (f.toIntOrNull() ?: 0) * 10
                    else -> f.take(3).toIntOrNull() ?: 0
                }
                out += LyricLine(m * 60f + s + ms / 1000f, text)
            }
        }
        return out.sortedBy { it.time }
    }
}
