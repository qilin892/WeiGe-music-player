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

    /** 封面：搜索结果里给的是相对路径，前面补 CDN 域名即可。 */
    fun coverUrl(song: Song): String =
        if (song.coverPath.isBlank()) "" else "http://img4.kuwo.cn/star/albumcover/${song.coverPath}"

    /** 歌词。返回按时间升序的行，没有歌词时返回空列表。 */
    suspend fun lyric(song: Song): List<LyricLine> = withContext(Dispatchers.IO) {
        try {
            val body = get("http://m.kuwo.cn/newh5/singles/songinfoandlrc?musicId=${song.id}")
            val list = JSONObject(body).optJSONObject("data")?.optJSONArray("lrclist")
                ?: return@withContext emptyList()

            val lines = ArrayList<LyricLine>(list.length())
            for (i in 0 until list.length()) {
                val o = list.optJSONObject(i) ?: continue
                val t = o.optString("time").toFloatOrNull() ?: continue
                val text = o.optString("lineLyric").trim()
                if (text.isEmpty()) continue
                lines += LyricLine(t, text)
            }
            lines.sortedBy { it.time }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
