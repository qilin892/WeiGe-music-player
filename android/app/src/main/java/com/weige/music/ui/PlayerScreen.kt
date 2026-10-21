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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.weige.music.model.LyricLine
import com.weige.music.model.Quality
import com.weige.music.play.PlayState

@Composable
fun PlayerScreen(
    state: PlayState,
    lyric: List<LyricLine>,
    lyricIndex: Int,
    onBack: () -> Unit,
    onToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    onQuality: (Quality) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onAddToPlaylist: () -> Unit,
    waiting: Boolean = false,
    favorite: Boolean = false,
    onToggleFavorite: () -> Unit = {},
    lyricLoading: Boolean = false,
) {
    val song = state.song

    Column(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Palette.Panel2, Palette.Bg),
                )
            )
    ) {
        // 顶栏
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "收起", tint = Palette.Text2)
            }
            Text(
                text = if (waiting) "正在解析音源" else "正在播放",
                fontSize = 11.sp,
                letterSpacing = 1.6.sp,
                color = if (waiting) Palette.Accent else Palette.Text3,
                modifier = Modifier.weight(1f),
            )
            // 收藏当前歌曲。空心/实心 + 强调色区分状态
            IconButton(onClick = onToggleFavorite, modifier = Modifier.size(38.dp)) {
                Icon(
                    imageVector = if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    contentDescription = if (favorite) "取消收藏" else "收藏",
                    tint = if (favorite) Palette.Accent else Palette.Text2,
                    modifier = Modifier.size(21.dp),
                )
            }
            AddToPlaylistButton(onClick = onAddToPlaylist)
            QualityChips(state.quality, onQuality)
        }

        Spacer(Modifier.height(6.dp))

        // 封面。解析音源时盖一层遮罩加转圈 —— 这是全屏里最显眼的等待标识
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box {
                CoverArt(song = song, size = 236.dp, radius = 14.dp, hd = true)
                if (waiting) {
                    Column(
                        modifier = Modifier
                            .size(236.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color.Black.copy(alpha = 0.46f)),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator(
                            color = Palette.Accent,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(34.dp),
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "正在解析音源",
                            fontSize = 12.sp,
                            color = Color.White,
                            letterSpacing = 0.6.sp,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        VuMeter(
            playing = state.playing,
            bars = 26,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 58.dp).height(20.dp),
        )

        Spacer(Modifier.height(14.dp))

        // 曲目信息
        Column(Modifier.fillMaxWidth().padding(horizontal = 26.dp)) {
            Text(
                text = song?.title ?: "未在播放",
                fontSize = 21.sp,
                fontWeight = FontWeight.Bold,
                color = Palette.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = song?.artist.orEmpty(),
                fontSize = 12.5.sp,
                color = Palette.Text2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.height(16.dp))

        Progress(state, onSeek)

        Spacer(Modifier.height(6.dp))

        // 控制：上一首 / 播放暂停 / 下一首
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onPrevious, modifier = Modifier.size(52.dp)) {
                Icon(
                    imageVector = Icons.Rounded.SkipPrevious,
                    contentDescription = "上一首",
                    tint = Palette.Text2,
                    modifier = Modifier.size(30.dp),
                )
            }
            Spacer(Modifier.width(22.dp))
            Box(
                modifier = Modifier
                    .size(58.dp)
                    .clip(CircleShape)
                    .background(if (waiting) Palette.Accent.copy(alpha = 0.5f) else Palette.Accent)
                    .clickable(enabled = !waiting) { onToggle() },
                contentAlignment = Alignment.Center,
            ) {
                if (waiting) {
                    // 还没取到地址，播放键点了没意义；原位换成转圈，避免「点了没反应」的错觉
                    CircularProgressIndicator(
                        color = Palette.OnAccent,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(24.dp),
                    )
                } else {
                    Icon(
                        imageVector = if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (state.playing) "暂停" else "播放",
                        tint = Palette.OnAccent,
                        modifier = Modifier.size(30.dp),
                    )
                }
            }
            Spacer(Modifier.width(22.dp))
            IconButton(onClick = onNext, modifier = Modifier.size(52.dp)) {
                Icon(
                    imageVector = Icons.Rounded.SkipNext,
                    contentDescription = "下一首",
                    tint = Palette.Text2,
                    modifier = Modifier.size(30.dp),
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // 歌词
        LyricPane(lyric, lyricIndex, loading = lyricLoading, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun QualityChips(current: Quality, onPick: (Quality) -> Unit) {
    Row(Modifier.padding(end = 8.dp)) {
        Quality.entries.forEach { q ->
            val on = q == current
            Text(
                text = q.label,
                fontSize = 10.5.sp,
                color = if (on) Palette.OnAccent else Palette.Text3,
                modifier = Modifier
                    .padding(start = 5.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (on) Palette.Accent else Color.Transparent)
                    .clickable { onPick(q) }
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            )
        }
    }
}

@Composable
private fun Progress(state: PlayState, onSeek: (Long) -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }

    val duration = state.durationMs.coerceAtLeast(1L)
    val value = if (dragging) dragValue else (state.positionMs.toFloat() / duration).coerceIn(0f, 1f)

    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp)) {
        Slider(
            value = value,
            onValueChange = {
                dragging = true
                dragValue = it
            },
            onValueChangeFinished = {
                dragging = false
                onSeek((dragValue * duration).toLong())
            },
            colors = SliderDefaults.colors(
                thumbColor = Palette.Accent,
                activeTrackColor = Palette.Accent,
                inactiveTrackColor = Palette.Line,
            ),
            modifier = Modifier.fillMaxWidth().height(24.dp),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val shown = if (dragging) (dragValue * duration).toLong() else state.positionMs
            Text(timeText(shown), fontSize = 10.5.sp, color = Palette.Text3)
            Text(timeText(state.durationMs), fontSize = 10.5.sp, color = Palette.Text3)
        }
    }
}

@Composable
private fun LyricPane(
    lines: List<LyricLine>,
    current: Int,
    loading: Boolean = false,
    modifier: Modifier = Modifier,
) {
    if (lines.isEmpty()) {
        Row(
            modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (loading) {
                CircularProgressIndicator(
                    color = Palette.Text3,
                    strokeWidth = 1.4.dp,
                    modifier = Modifier.size(12.dp),
                )
                Spacer(Modifier.width(7.dp))
            }
            Text(
                text = if (loading) "歌词加载中…" else "暂无歌词",
                fontSize = 12.sp,
                color = Palette.Text3,
            )
        }
        return
    }

    val listState = rememberLazyListState()

    // 当前行变化时把它滚到中间
    LaunchedEffect(current) {
        if (current >= 0) {
            listState.animateScrollToItem((current - 2).coerceAtLeast(0))
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth().padding(horizontal = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        itemsIndexed(lines) { i, line ->
            val on = i == current
            Text(
                text = line.text,
                fontSize = if (on) 14.5.sp else 12.5.sp,
                color = if (on) Palette.Accent else Palette.Text3,
                fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
            )
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

private fun timeText(ms: Long): String {
    if (ms <= 0) return "0:00"
    val total = ms / 1000
    return "%d:%02d".format(total / 60, total % 60)
}
