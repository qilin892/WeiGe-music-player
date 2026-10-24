package com.weige.music.model

/**
 * 播放模式。
 *
 * 映射到 ExoPlayer 的三个属性组合：
 *   顺序   → repeatMode=OFF，   shuffle=false   播完最后一首就停
 *   单曲   → repeatMode=ONE，   shuffle=false
 *   随机   → repeatMode=ALL，   shuffle=true    随机要配列表循环，否则摇到最后一首就断
 */
enum class PlayMode(val label: String) {
    SEQUENCE("顺序播放"),
    LOOP_ONE("单曲循环"),
    SHUFFLE("随机播放");

    fun next(): PlayMode = when (this) {
        SEQUENCE -> LOOP_ONE
        LOOP_ONE -> SHUFFLE
        SHUFFLE -> SEQUENCE
    }

    companion object {
        fun of(name: String?): PlayMode =
            values().firstOrNull { it.name == name } ?: SEQUENCE
    }
}
