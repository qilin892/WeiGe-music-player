package com.weige.music.play

import com.weige.music.model.Song
import java.util.concurrent.ConcurrentHashMap

/**
 * 队列里每首歌的元信息登记处。
 *
 * 为什么需要它：为了让通知栏/锁屏出现「上一首/下一首」，必须把**整个队列**交给 ExoPlayer
 * （只塞一个 MediaItem 时，播放器认为没有前后曲目，系统就不画那两个键）。
 *
 * 但每首歌的真实地址要跑 JS 脚本才拿得到，不可能提前全部解析。
 * 所以 MediaItem 的 URI 只带一个 `weige://<songId>?q=<音质>`，真正的地址由
 * [ResolvingDataSourceProvider] 在**要播到那一刻**回头查这张表。
 */
object SongRegistry {

    private val map = ConcurrentHashMap<String, Song>()

    fun putAll(list: List<Song>) {
        list.forEach { map[it.id] = it }
        // 队列一般就几十首，超了就清一批，避免长时间运行无限涨
        if (map.size > 300) {
            val keep = list.map { it.id }.toSet()
            map.keys.filterNot { keep.contains(it) }.take(map.size - 200).forEach { map.remove(it) }
        }
    }

    fun get(id: String): Song? = map[id]
}
