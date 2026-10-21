package com.weige.music.model

/**
 * 在线歌单，来自酷我官方歌单接口。
 *
 * 注意 [cover] 存的是**可直接加载的完整 URL**（已按 500 宽归一化），
 * 和本地歌曲用的 `Song.coverPath`（短路径）不是一回事 —— 在线歌单有两种封面源：
 * 专辑封面（img1.kuwo.cn/star/albumcover/…）和用户歌单封面（img.cdn.kwcdn.kuwo.cn）。
 */
data class OnlinePlaylist(
    val id: String,
    val name: String,
    val cover: String,
    val songCount: Int = 0,
    /** 已格式化成「xx万 / xx亿」，接口给的是原始数字 */
    val playCount: String = "",
    val author: String = "",
    val desc: String = "",
    /**
     * 接口给的 digest：8 = 普通歌单，13 = 专辑。
     * 专辑不能当歌单播放（pl.svc 拿不到歌），所以在列表阶段就被滤掉。
     */
    val digest: String = "8",
) {
    val key: String get() = id
}

/** 歌单分类下的一个标签 */
data class PlaylistTag(
    val id: String,
    val name: String,
    /** 接口路由用：digest=43 的「专区」类必须走 er.s，其余走 getTagPlayList */
    val digest: String,
)

/** 标签分组：专区 / 主题 / 心情 / 场景 / 年代 / 曲风流派 */
data class PlaylistTagGroup(
    val name: String,
    val tags: List<PlaylistTag>,
)

/** 一页歌单 */
data class PlaylistPage(
    val list: List<OnlinePlaylist>,
    val hasMore: Boolean,
)

/** 歌单详情：头部信息 + 歌曲 */
data class PlaylistDetail(
    val info: OnlinePlaylist,
    val songs: List<Song>,
)
