package com.weige.music.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.weige.music.model.Playlist
import com.weige.music.model.Song

/* ------------------------------------------------------------ 歌单列表 */

@Composable
fun PlaylistsTab(
    playlists: List<Playlist>,
    onOpen: (String) -> Unit,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 18.dp, end = 10.dp, top = 10.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (playlists.isEmpty()) "还没有歌单" else "共 ${playlists.size} 个歌单",
                fontSize = 11.sp,
                letterSpacing = 1.2.sp,
                color = Palette.Text3,
                modifier = Modifier.weight(1f),
            )
            Row(
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onCreate)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.Add,
                    contentDescription = null,
                    tint = Palette.Accent,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text("新建歌单", fontSize = 12.5.sp, color = Palette.Accent)
            }
        }

        if (playlists.isEmpty()) {
            Hint("建个歌单，把喜欢的歌收进去", Modifier.fillMaxWidth().padding(top = 60.dp))
            return@Column
        }

        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(playlists.reversed()) { _, p ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(p.id) }
                        .padding(horizontal = 18.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Palette.Panel2),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Rounded.PlaylistPlay,
                            contentDescription = null,
                            tint = Palette.AccentDim,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            p.name,
                            fontSize = 15.sp,
                            color = Palette.Text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text("${p.count} 首", fontSize = 11.5.sp, color = Palette.Text2)
                    }
                    Icon(
                        Icons.Rounded.KeyboardArrowRight,
                        contentDescription = null,
                        tint = Palette.Text3,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            item { Spacer(Modifier.height(90.dp)) }
        }
    }
}

/* ------------------------------------------------------------ 歌单详情 */

@Composable
fun PlaylistDetailView(
    playlist: Playlist,
    currentId: String?,
    isFavorite: (Song) -> Boolean,
    onToggleFavorite: (Song) -> Unit,
    onBack: () -> Unit,
    onPlay: (Song, List<Song>) -> Unit,
    onRemove: (Song) -> Unit,
    onDeletePlaylist: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmDelete by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 6.dp, end = 8.dp, top = 4.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "返回", tint = Palette.Text2)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    playlist.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Palette.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text("${playlist.count} 首", fontSize = 11.sp, color = Palette.Text3)
            }
            IconButton(onClick = { confirmDelete = true }, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Rounded.DeleteOutline,
                    contentDescription = "删除歌单",
                    tint = Palette.Text3,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        if (playlist.songs.isEmpty()) {
            Hint("歌单是空的，去播放页点「加入歌单」", Modifier.fillMaxWidth().padding(top = 60.dp))
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(playlist.songs) { i, song ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPlay(song, playlist.songs) }
                            .padding(start = 18.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "%02d".format(i + 1),
                            fontSize = 11.sp,
                            color = if (song.id == currentId) Palette.Accent else Palette.Text3,
                            modifier = Modifier.width(24.dp),
                        )
                        CoverArt(song = song, size = 42.dp, radius = 6.dp)
                        Spacer(Modifier.width(11.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                song.title,
                                fontSize = 14.5.sp,
                                color = if (song.id == currentId) Palette.Accent else Palette.Text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                listOf(song.artist, song.album).filter { it.isNotBlank() }.joinToString(" · "),
                                fontSize = 11.5.sp,
                                color = Palette.Text2,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(song.durationText, fontSize = 11.sp, color = Palette.Text3)
                        IconButton(onClick = { onToggleFavorite(song) }, modifier = Modifier.size(38.dp)) {
                            Icon(
                                imageVector = if (isFavorite(song)) {
                                    Icons.Rounded.Favorite
                                } else {
                                    Icons.Rounded.FavoriteBorder
                                },
                                contentDescription = null,
                                tint = if (isFavorite(song)) Palette.Accent else Palette.Text3,
                                modifier = Modifier.size(17.dp),
                            )
                        }
                        IconButton(onClick = { onRemove(song) }, modifier = Modifier.size(38.dp)) {
                            Icon(
                                Icons.Rounded.DeleteOutline,
                                contentDescription = "从歌单移除",
                                tint = Palette.Text3,
                                modifier = Modifier.size(17.dp),
                            )
                        }
                    }
                }
                item { Spacer(Modifier.height(90.dp)) }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除歌单", color = Palette.Text) },
            text = { Text("「${playlist.name}」里的 ${playlist.count} 首歌会一起移除（不影响收藏）。", color = Palette.Text2) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDeletePlaylist()
                }) { Text("删除", color = Palette.Accent) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消", color = Palette.Text3) }
            },
            containerColor = Palette.Panel,
        )
    }
}

/* ------------------------------------------------------------ 输入弹窗 */

@Composable
fun InputDialog(
    title: String,
    placeholder: String,
    confirmText: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, color = Palette.Text, fontSize = 16.sp) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                placeholder = { Text(placeholder, color = Palette.Text3, fontSize = 13.sp) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Palette.Text,
                    unfocusedTextColor = Palette.Text,
                    focusedBorderColor = Palette.Accent,
                    unfocusedBorderColor = Palette.Line,
                    cursorColor = Palette.Accent,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (text.isNotBlank()) {
                        onConfirm(text)
                        onDismiss()
                    }
                }
            ) { Text(confirmText, color = Palette.Accent) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = Palette.Text3) }
        },
        containerColor = Palette.Panel,
    )
}

/* --------------------------------------------------- 加入歌单（含新建） */

@Composable
fun AddToPlaylistDialog(
    song: Song,
    playlists: List<Playlist>,
    onPick: (String) -> Unit,
    onCreateAndAdd: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var newName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("加入歌单", color = Palette.Text, fontSize = 16.sp)
                Text(
                    song.title,
                    color = Palette.Text3,
                    fontSize = 11.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        },
        text = {
            Column {
                if (playlists.isEmpty()) {
                    Text("还没有歌单，下面新建一个", color = Palette.Text3, fontSize = 12.5.sp)
                } else {
                    LazyColumn(Modifier.height(if (playlists.size > 4) 200.dp else 200.dp)) {
                        itemsIndexed(playlists.reversed()) { _, p ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { onPick(p.id) }
                                    .padding(horizontal = 6.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Rounded.PlaylistPlay,
                                    contentDescription = null,
                                    tint = Palette.AccentDim,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(9.dp))
                                Text(
                                    p.name,
                                    color = Palette.Text,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Text("${p.count}", color = Palette.Text3, fontSize = 11.sp)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        singleLine = true,
                        placeholder = { Text("新建歌单名", color = Palette.Text3, fontSize = 12.5.sp) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Palette.Text,
                            unfocusedTextColor = Palette.Text,
                            focusedBorderColor = Palette.Accent,
                            unfocusedBorderColor = Palette.Line,
                            cursorColor = Palette.Accent,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(6.dp))
                    TextButton(
                        onClick = {
                            if (newName.isNotBlank()) onCreateAndAdd(newName)
                        }
                    ) { Text("新建并加入", color = Palette.Accent, fontSize = 12.5.sp) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭", color = Palette.Text3) }
        },
        containerColor = Palette.Panel,
    )
}

/** 给播放页用的「加入歌单」小按钮 */
@Composable
fun AddToPlaylistButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier.size(40.dp)) {
        Icon(
            Icons.Rounded.PlaylistPlay,
            contentDescription = "加入歌单",
            tint = Palette.Text2,
            modifier = Modifier.size(21.dp),
        )
    }
}
