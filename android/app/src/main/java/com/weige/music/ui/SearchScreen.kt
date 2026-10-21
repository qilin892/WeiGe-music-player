package com.weige.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.weige.music.model.Playlist
import com.weige.music.model.Song

@Composable
fun SearchScreen(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    results: List<Song>,
    searching: Boolean,
    recent: List<Song>,
    favorites: List<Song>,
    playlists: List<Playlist>,
    openedPlaylist: Playlist?,
    currentId: String?,
    dark: Boolean,
    onToggleTheme: () -> Unit,
    isFavorite: (Song) -> Boolean,
    onToggleFavorite: (Song) -> Unit,
    onPick: (Song, List<Song>) -> Unit,
    onOpenPlaylist: (String) -> Unit,
    onBackPlaylist: () -> Unit,
    onCreatePlaylist: () -> Unit,
    onDeletePlaylist: (String) -> Unit,
    onRemoveFromPlaylist: (String, Song) -> Unit,
    modifier: Modifier = Modifier,
) {
    var tab by remember { mutableIntStateOf(0) }

    Column(modifier.fillMaxSize()) {
        Header(dark = dark, onToggleTheme = onToggleTheme)
        Tabs(tab) { tab = it }

        when (tab) {
            0 -> Column(Modifier.fillMaxSize()) {
                SearchField(query, onQueryChange, onSearch)
                Spacer(Modifier.height(6.dp))

                when {
                    searching -> Box(
                        Modifier.fillMaxWidth().padding(top = 40.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            color = Palette.Accent,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(22.dp),
                        )
                    }

                    results.isNotEmpty() -> LazyColumn(Modifier.fillMaxSize()) {
                        itemsIndexed(results) { i, song ->
                            SongRow(
                                index = i + 1,
                                song = song,
                                playing = song.id == currentId,
                                favorite = isFavorite(song),
                                onFav = { onToggleFavorite(song) },
                                onClick = { onPick(song, results) },
                            )
                        }
                        item { Spacer(Modifier.height(90.dp)) }
                    }

                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        if (recent.isNotEmpty()) {
                            item { SectionLabel("最近播放") }
                            items(recent) { song ->
                                SongRow(
                                    index = 0,
                                    song = song,
                                    playing = song.id == currentId,
                                    favorite = isFavorite(song),
                                    onFav = { onToggleFavorite(song) },
                                    onClick = { onPick(song, recent) },
                                )
                            }
                        } else {
                            item { Hint("搜点什么吧", Modifier.fillMaxWidth().padding(top = 70.dp)) }
                        }
                        item { Spacer(Modifier.height(90.dp)) }
                    }
                }
            }

            1 -> if (favorites.isEmpty()) {
                Hint("还没有收藏，在列表里点心形试试", Modifier.fillMaxSize().padding(top = 70.dp))
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    item { SectionLabel("共 ${favorites.size} 首") }
                    itemsIndexed(favorites) { i, song ->
                        SongRow(
                            index = i + 1,
                            song = song,
                            playing = song.id == currentId,
                            favorite = true,
                            onFav = { onToggleFavorite(song) },
                            onClick = { onPick(song, favorites) },
                        )
                    }
                    item { Spacer(Modifier.height(90.dp)) }
                }
            }

            else -> if (openedPlaylist != null) {
                PlaylistDetailView(
                    playlist = openedPlaylist,
                    currentId = currentId,
                    isFavorite = isFavorite,
                    onToggleFavorite = onToggleFavorite,
                    onBack = onBackPlaylist,
                    onPlay = onPick,
                    onRemove = { onRemoveFromPlaylist(openedPlaylist.id, it) },
                    onDeletePlaylist = { onDeletePlaylist(openedPlaylist.id) },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                PlaylistsTab(
                    playlists = playlists,
                    onOpen = onOpenPlaylist,
                    onCreate = onCreatePlaylist,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun Header(dark: Boolean, onToggleTheme: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 12.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(Palette.Panel2),
            contentAlignment = Alignment.Center,
        ) {
            WMark(Modifier.size(22.dp))
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text("伟哥音乐", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Palette.Text)
            Text("WEIGE HI-FI", fontSize = 9.5.sp, letterSpacing = 1.6.sp, color = Palette.Text3)
        }
        IconButton(onClick = onToggleTheme) {
            Icon(
                imageVector = if (dark) Icons.Rounded.LightMode else Icons.Rounded.DarkMode,
                contentDescription = if (dark) "切换到白天模式" else "切换到夜间模式",
                tint = Palette.Text2,
            )
        }
    }
}

/** 搜索 / 收藏 两个页签，下划线指示当前项，和整体的硬件面板风格一致 */
@Composable
private fun Tabs(current: Int, onSelect: (Int) -> Unit) {
    val labels = listOf("搜索", "收藏", "歌单")
    // start 8 + 内部 10 = 18，和下方列表的文字左边界对齐
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 18.dp, bottom = 6.dp)) {
        labels.forEachIndexed { i, label ->
            val on = i == current
            Box(
                modifier = Modifier
                    // 先裁形状再挂 clickable：涟漪只覆盖「文字 + 内边距」，
                    // 不会被外层间距撑开导致看起来偏左
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onSelect(i) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = label,
                        fontSize = 14.sp,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                        color = if (on) Palette.Accent else Palette.Text3,
                    )
                    Spacer(Modifier.height(4.dp))
                    Box(
                        Modifier
                            .width(if (on) 16.dp else 0.dp)
                            .height(2.dp)
                            .background(Palette.Accent),
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit, onSearch: () -> Unit) {
    TextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        placeholder = { Text("歌名，或「歌名 歌手」", color = Palette.Text3, fontSize = 14.sp) },
        trailingIcon = {
            IconButton(onClick = onSearch) {
                Icon(Icons.Rounded.Search, contentDescription = "搜索", tint = Palette.Accent)
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        shape = RoundedCornerShape(10.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Palette.Panel,
            unfocusedContainerColor = Palette.Panel,
            focusedTextColor = Palette.Text,
            unfocusedTextColor = Palette.Text,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            cursorColor = Palette.Accent,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp)
            .border(1.dp, Palette.Line.copy(alpha = 0.75f), RoundedCornerShape(10.dp)),
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        fontSize = 11.sp,
        letterSpacing = 1.4.sp,
        color = Palette.Text3,
        modifier = Modifier.padding(start = 18.dp, top = 14.dp, bottom = 8.dp),
    )
}

@Composable
private fun SongRow(
    index: Int,
    song: Song,
    playing: Boolean,
    favorite: Boolean,
    onFav: () -> Unit,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 18.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (index > 0) "%02d".format(index) else "·",
            fontSize = 11.sp,
            color = if (playing) Palette.Accent else Palette.Text3,
            modifier = Modifier.width(24.dp),
        )
        CoverArt(song = song, size = 42.dp, radius = 6.dp)
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(
                text = song.title,
                fontSize = 14.5.sp,
                color = if (playing) Palette.Accent else Palette.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = listOf(song.artist, song.album).filter { it.isNotBlank() }.joinToString(" · "),
                fontSize = 11.5.sp,
                color = Palette.Text2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(song.durationText, fontSize = 11.sp, color = Palette.Text3)
        IconButton(onClick = onFav, modifier = Modifier.size(40.dp)) {
            Icon(
                imageVector = if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                contentDescription = if (favorite) "取消收藏" else "收藏",
                tint = if (favorite) Palette.Accent else Palette.Text3,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
