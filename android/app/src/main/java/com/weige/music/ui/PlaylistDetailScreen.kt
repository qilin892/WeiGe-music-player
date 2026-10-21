package com.weige.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import com.weige.music.model.OnlinePlaylist
import com.weige.music.model.Song

/**
 * 在线歌单详情。点任意一首会把**整个歌单**入队，所以上下首就是歌单里的顺序。
 */
@Composable
fun PlaylistDetailScreen(
    playlist: OnlinePlaylist,
    songs: List<Song>,
    loading: Boolean,
    currentId: String?,
    isFavorite: (Song) -> Boolean,
    onToggleFavorite: (Song) -> Unit,
    onBack: () -> Unit,
    onPlayAll: () -> Unit,
    onPlay: (Song) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 2.dp, end = 14.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.Rounded.ArrowBack,
                    contentDescription = "返回",
                    tint = Palette.Text2,
                )
            }
            Text(
                "在线歌单",
                fontSize = 11.sp,
                letterSpacing = 1.6.sp,
                color = Palette.Text3,
                modifier = Modifier.weight(1f),
            )
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            item { Head(playlist, songs.size, loading, onPlayAll) }

            if (songs.isNotEmpty()) {
                item {
                    Text(
                        text = "共 ${songs.size} 首",
                        fontSize = 11.sp,
                        letterSpacing = 1.sp,
                        color = Palette.Text3,
                        modifier = Modifier.padding(start = 18.dp, top = 16.dp, bottom = 6.dp),
                    )
                }
            }

            itemsIndexed(songs, key = { _, s -> s.id }) { i, song ->
                PLSongRow(
                    index = i + 1,
                    song = song,
                    playing = song.id == currentId,
                    favorite = isFavorite(song),
                    onFav = { onToggleFavorite(song) },
                    onClick = { onPlay(song) },
                )
            }

            if (loading) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 36.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            color = Palette.Accent,
                            strokeWidth = 1.8.dp,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(9.dp))
                        Text("正在载入歌单…", fontSize = 12.sp, color = Palette.Text3)
                    }
                }
            }
        }
    }
}

@Composable
private fun Head(
    p: OnlinePlaylist,
    count: Int,
    loading: Boolean,
    onPlayAll: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        var failed by remember(p.cover) { mutableStateOf(false) }

        Row(verticalAlignment = Alignment.Top) {
            Box(
                Modifier
                    .size(112.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Palette.Panel2)
            ) {
                WMark(Modifier.fillMaxSize().padding(26.dp), color = Palette.AccentDim)
                if (p.cover.isNotEmpty() && !failed) {
                    AsyncImage(
                        model = p.cover,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                        onState = { state ->
                            if (state is AsyncImagePainter.State.Error) failed = true
                        },
                    )
                }
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = p.name.ifBlank { "歌单" },
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Palette.Text,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = listOf(p.author, if (count > 0) "$count 首" else "")
                        .filter { it.isNotBlank() }
                        .joinToString(" · "),
                    fontSize = 11.5.sp,
                    color = Palette.Text2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (p.playCount.isNotEmpty()) {
                    Spacer(Modifier.height(3.dp))
                    Text("播放 ${p.playCount}", fontSize = 11.sp, color = Palette.Text3)
                }
            }
        }

        Spacer(Modifier.height(13.dp))

        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Palette.Accent)
                .clickable(enabled = !loading && count > 0, onClick = onPlayAll)
                .padding(vertical = 11.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (loading) {
                CircularProgressIndicator(
                    color = Palette.OnAccent,
                    strokeWidth = 1.8.dp,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("正在载入…", color = Palette.OnAccent, fontSize = 13.5.sp)
            } else {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = Palette.OnAccent,
                    modifier = Modifier.size(19.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "播放全部",
                    color = Palette.OnAccent,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun PLSongRow(
    index: Int,
    song: Song,
    playing: Boolean,
    favorite: Boolean,
    onFav: () -> Unit,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 18.dp, end = 4.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "%02d".format(index),
            fontSize = 11.sp,
            color = if (playing) Palette.Accent else Palette.Text3,
            modifier = Modifier.width(24.dp),
        )
        CoverArt(song = song, size = 40.dp, radius = 6.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = song.title,
                fontSize = 14.sp,
                color = if (playing) Palette.Accent else Palette.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = listOf(song.artist, song.album)
                    .filter { it.isNotBlank() }
                    .joinToString(" · "),
                fontSize = 11.sp,
                color = Palette.Text2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onFav, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                contentDescription = if (favorite) "取消收藏" else "收藏",
                tint = if (favorite) Palette.Accent else Palette.Text3,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
