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
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.SkipNext
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
                "正在播放",
                fontSize = 11.sp,
                letterSpacing = 1.6.sp,
                color = Palette.Text3,
                modifier = Modifier.weight(1f),
            )
            AddToPlaylistButton(onClick = onAddToPlaylist)
            QualityChips(state.quality, onQuality)
        }

        Spacer(Modifier.height(6.dp))

        // 封面
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            CoverArt(song = song, size = 236.dp, radius = 14.dp)
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
                    .background(Palette.Accent)
                    .clickable { onToggle() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (state.playing) "暂停" else "播放",
                    tint = Palette.OnAccent,
                    modifier = Modifier.size(30.dp),
                )
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
        LyricPane(lyric, lyricIndex, Modifier.weight(1f))
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
private fun LyricPane(lines: List<LyricLine>, current: Int, modifier: Modifier = Modifier) {
    if (lines.isEmpty()) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text("暂无歌词", fontSize = 12.sp, color = Palette.Text3)
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
