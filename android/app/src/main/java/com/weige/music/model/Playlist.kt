package com.weige.music.model

/** 用户自建歌单 */
data class Playlist(
    val id: String,
    val name: String,
    val songs: List<Song>,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val count: Int get() = songs.size
}
