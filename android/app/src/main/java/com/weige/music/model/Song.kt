package com.weige.music.model

/** 一首歌。id 是酷我的 rid，跨平台不能复用，所以只在本平台内使用。 */
data class Song(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationSec: Int,
    val coverPath: String = "",
) {
    val durationText: String
        get() {
            if (durationSec <= 0) return "--:--"
            val m = durationSec / 60
            val s = durationSec % 60
            return "%d:%02d".format(m, s)
        }

    /** 用于 MVVM 里判断是否同一首，也用于列表 key */
    val key: String get() = "$id"

    companion object {
        /** 搜索结果里常见的"非原版"标记，这些会被排到后面 */
        private val BAD = listOf(
            "伴奏", "ktv", "翻唱", "dj版", "dj ", "remix", "纯音乐", "铃声",
            "片段", "cover", "清唱", "现场", "live", "演唱会", "音乐节", "未修音",
        )

        fun isBadVersion(song: Song): Boolean {
            val t = (song.title + " " + song.artist).lowercase()
            return BAD.any { t.contains(it) }
        }
    }
}

/** 一行歌词，time 单位秒 */
data class LyricLine(val time: Float, val text: String)

/** 音质档位，对应取流接口的 level 参数 */
enum class Quality(val level: String, val label: String) {
    STANDARD("standard", "标准"),
    HIGH("exhigh", "高品质"),
    LOSSLESS("lossless", "无损");

    companion object {
        /** 从 level 字符串反查，用于解析 MediaItem URI 里带的音质 */
        fun of(level: String?): Quality =
            entries.firstOrNull { it.level == level } ?: HIGH
    }
}
