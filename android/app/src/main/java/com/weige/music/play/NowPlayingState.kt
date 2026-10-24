package com.weige.music.play

import android.graphics.Bitmap

/**
 * 通知栏要用、但不属于播放器状态的两样东西：当前歌词行 + 封面位图。
 *
 * 为什么单独放一份：Media3 想换通知文案，常规做法是 `replaceMediaItem` 改 metadata，
 * 但那会让播放器**重建媒体源**——每句歌词重新解析一次直链，进度条跟着跳。
 * 所以这里不碰播放器，只存一份「最新展示数据」，由 NotificationProvider 自己读，
 * 变了就手动刷一次通知。
 */
object NowPlayingState {

    @Volatile
    var lyricLine: String = ""
        private set

    @Volatile
    private var coverSongId: String? = null

    @Volatile
    private var cover: Bitmap? = null

    /** 有新数据时由 PlaybackService 接上，用来触发通知刷新 */
    @Volatile
    var onChanged: (() -> Unit)? = null

    /** 返回 true 表示歌词真的变了，调用方据此决定要不要刷通知 */
    fun setLyric(line: String): Boolean {
        if (line == lyricLine) return false
        lyricLine = line
        return true
    }

    fun setCover(songId: String, bitmap: Bitmap?) {
        coverSongId = songId
        cover = bitmap
    }

    fun coverOf(songId: String?): Bitmap? =
        if (songId != null && songId == coverSongId) cover else null

    fun reset() {
        lyricLine = ""
        coverSongId = null
        cover = null
    }
}
