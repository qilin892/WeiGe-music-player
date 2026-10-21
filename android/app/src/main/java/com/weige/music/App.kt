package com.weige.music

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.weige.music.play.PlayState
import com.weige.music.ui.AddToPlaylistDialog
import com.weige.music.ui.CoverArt
import com.weige.music.ui.InputDialog
import com.weige.music.ui.Palette
import com.weige.music.ui.PlayerScreen
import com.weige.music.ui.SearchScreen
import com.weige.music.ui.ThinProgress
import kotlinx.coroutines.delay

@Composable
fun AppRoot(vm: AppViewModel) {
    val state by vm.playState.collectAsStateWithLifecycle()
    var showCreatePlaylist by remember { mutableStateOf(false) }

    BackHandler(enabled = vm.showPlayer) { vm.showPlayer = false }

    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.Bg)
            .systemBarsPadding()
    ) {
        Column(Modifier.fillMaxSize()) {
            if (vm.showPlayer && state.song != null) {
                PlayerScreen(
                    state = state,
                    lyric = vm.lyric,
                    lyricIndex = vm.lyricIndex,
                    onBack = { vm.showPlayer = false },
                    onToggle = { vm.toggle() },
                    onSeek = { vm.seekTo(it) },
                    onQuality = { vm.setQuality(it) },
                    onPrevious = { vm.previous() },
                    onNext = { vm.next() },
                    onAddToPlaylist = { state.song?.let { vm.pickSongForPlaylist(it) } },
                )
            } else {
                SearchScreen(
                    query = vm.query,
                    onQueryChange = { vm.onQueryChange(it) },
                    onSearch = { vm.search() },
                    results = vm.results,
                    searching = vm.searching,
                    recent = vm.recent,
                    favorites = vm.favorites,
                    playlists = vm.playlists,
                    openedPlaylist = vm.openedPlaylist,
                    currentId = state.song?.id,
                    dark = vm.dark,
                    onToggleTheme = { vm.toggleTheme() },
                    isFavorite = { vm.isFavorite(it) },
                    onToggleFavorite = { vm.toggleFavorite(it) },
                    onPick = { song, list -> vm.play(song, list) },
                    onOpenPlaylist = { vm.openedPlaylistId = it },
                    onBackPlaylist = { vm.openedPlaylistId = null },
                    onCreatePlaylist = { showCreatePlaylist = true },
                    onDeletePlaylist = { vm.deletePlaylist(it) },
                    onRemoveFromPlaylist = { pid, song -> vm.removeFromPlaylist(pid, song.id) },
                    modifier = Modifier.weight(1f),
                )
                if (state.song != null) {
                    MiniPlayer(
                        state = state,
                        onClick = { vm.showPlayer = true },
                        onToggle = { vm.toggle() },
                        onNext = { vm.next() },
                    )
                }
            }
        }

        // 提示条盖在最上层，不能放进上面的 Column，否则 fillMaxSize 会把内容挤扁
        Toast(vm.message) { vm.dismissMessage() }

        // 新建歌单
        if (showCreatePlaylist) {
            InputDialog(
                title = "新建歌单",
                placeholder = "给歌单起个名字",
                confirmText = "创建",
                onConfirm = { vm.createPlaylist(it) },
                onDismiss = { showCreatePlaylist = false },
            )
        }

        // 加入歌单（从播放页触发）
        vm.addToPlaylistSong?.let { song ->
            AddToPlaylistDialog(
                song = song,
                playlists = vm.playlists,
                onPick = { pid -> vm.addToPlaylist(pid, song) },
                onCreateAndAdd = { name ->
                    vm.createPlaylist(name)
                    // createPlaylist 之后列表已经更新，取最新那个
                    vm.playlists.lastOrNull()?.let { vm.addToPlaylist(it.id, song) }
                },
                onDismiss = { vm.addToPlaylistSong = null },
            )
        }

        // 取流要走 JS 脚本，可能要等几秒，给个明确的进行中提示
        if (vm.resolving) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .padding(bottom = 96.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                        .background(Palette.Panel2)
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                ) {
                    CircularProgressIndicator(
                        color = Palette.Accent,
                        strokeWidth = 1.6.dp,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("正在解析音源…", color = Palette.Text, fontSize = 12.5.sp)
                }
            }
        }
    }
}

@Composable
private fun MiniPlayer(
    state: PlayState,
    onClick: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
) {
    val duration = state.durationMs.coerceAtLeast(1L)
    val progress = (state.positionMs.toFloat() / duration).coerceIn(0f, 1f)

    Column(
        Modifier
            .fillMaxWidth()
            .background(Palette.Panel)
            .clickable(onClick = onClick)
    ) {
        ThinProgress(progress, Modifier.fillMaxWidth().height(2.dp))
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CoverArt(song = state.song, size = 40.dp, radius = 6.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = state.song?.title.orEmpty(),
                    fontSize = 13.5.sp,
                    color = Palette.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = state.song?.artist.orEmpty(),
                    fontSize = 11.sp,
                    color = Palette.Text2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onNext, modifier = Modifier.size(38.dp)) {
                Icon(
                    imageVector = Icons.Rounded.SkipNext,
                    contentDescription = "下一首",
                    tint = Palette.Text2,
                    modifier = Modifier.size(22.dp),
                )
            }
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(Palette.Accent)
                    .clickable { onToggle() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = Palette.OnAccent,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

/** 极简提示条，2.4 秒自动消失 */
@Composable
private fun Toast(message: String?, onDone: () -> Unit) {
    if (message == null) return
    var visible by remember(message) { mutableStateOf(true) }

    LaunchedEffect(message) {
        visible = true
        delay(2400)
        visible = false
        onDone()
    }

    if (!visible) return

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Text(
            text = message,
            color = Palette.Text,
            fontSize = 12.5.sp,
            modifier = Modifier
                .padding(bottom = 96.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                .background(Palette.Panel2)
                .padding(horizontal = 14.dp, vertical = 9.dp),
        )
    }
}
