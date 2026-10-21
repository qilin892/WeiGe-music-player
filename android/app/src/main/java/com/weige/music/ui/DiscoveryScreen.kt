package com.weige.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import com.weige.music.model.OnlinePlaylist
import com.weige.music.model.PlaylistTag
import com.weige.music.model.PlaylistTagGroup
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * 发现页：分类 + 标签 + 歌单瀑布流。
 *
 * 数据来自酷我官方歌单接口（[com.weige.music.data.PlaylistApi]），
 * 和洛雪的 `Views/SongList` 同一套链路。
 */
@Composable
fun DiscoveryScreen(
    tagGroups: List<PlaylistTagGroup>,
    selectedGroup: Int,
    selectedTag: PlaylistTag?,
    playlists: List<OnlinePlaylist>,
    loading: Boolean,
    hasMore: Boolean,
    error: String?,
    onSelectGroup: (Int) -> Unit,
    onSelectTag: (PlaylistTag) -> Unit,
    onOpen: (OnlinePlaylist) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val gridState = rememberLazyGridState()

    // 触底自动翻页：最后一个可见项离末尾不到 4 个就去要下一批
    LaunchedEffect(gridState, hasMore, loading, playlists.size) {
        snapshotFlow {
            val info = gridState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: 0) to info.totalItemsCount
        }
            .distinctUntilChanged()
            .collect { (last, total) ->
                if (hasMore && !loading && total > 0 && last >= total - 4) onLoadMore()
            }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        state = gridState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 2.dp, bottom = 92.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            GroupRow(tagGroups, selectedGroup, onSelectGroup)
        }

        if (selectedGroup >= 0) {
            val group = tagGroups.getOrNull(selectedGroup)
            if (group != null && group.tags.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    TagRow(group.tags, selectedTag, onSelectTag)
                }
            }
        }

        items(playlists, key = { it.id }) { p ->
            PlaylistCard(p, onClick = { onOpen(p) })
        }

        if (loading || error != null || playlists.isEmpty() || !hasMore) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Footer(
                    loading = loading,
                    error = error,
                    hasMore = hasMore,
                    empty = playlists.isEmpty(),
                    onRetry = onRetry,
                )
            }
        }
    }
}

/** 第一行：推荐 + 各分组名 */
@Composable
private fun GroupRow(groups: List<PlaylistTagGroup>, selected: Int, onSelect: (Int) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Pill(text = "推荐", on = selected < 0) { onSelect(-1) }
        }
        itemsIndexed(groups) { i, g ->
            Pill(text = g.name, on = i == selected) { onSelect(i) }
        }
    }
}

/** 第二行：当前分组下的标签 */
@Composable
private fun TagRow(tags: List<PlaylistTag>, selected: PlaylistTag?, onSelect: (PlaylistTag) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        itemsIndexed(tags) { _, t ->
            val on = selected?.id == t.id
            Text(
                text = t.name,
                fontSize = 11.5.sp,
                color = if (on) Palette.Accent else Palette.Text3,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (on) Palette.AccentDim else Color.Transparent)
                    .border(1.dp, if (on) Palette.Accent else Palette.Line, RoundedCornerShape(50))
                    .clickable { onSelect(t) }
                    .padding(horizontal = 11.dp, vertical = 5.dp),
            )
        }
    }
}

@Composable
private fun Pill(text: String, on: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        fontSize = 12.5.sp,
        fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
        color = if (on) Palette.OnAccent else Palette.Text2,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (on) Palette.Accent else Palette.Panel2)
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 7.dp),
    )
}

@Composable
private fun PlaylistCard(p: OnlinePlaylist, onClick: () -> Unit) {
    // W 一直垫在底层，图片加载失败就留在那儿（不留灰块）
    var failed by remember(p.cover) { mutableStateOf(false) }

    Column(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(8.dp))
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
            if (p.playCount.isNotEmpty()) {
                Text(
                    text = p.playCount,
                    fontSize = 10.sp,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.Black.copy(alpha = 0.45f))
                        .padding(horizontal = 5.dp, vertical = 1.5.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = p.name,
            fontSize = 12.5.sp,
            lineHeight = 17.sp,
            color = Palette.Text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (p.songCount > 0) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${p.songCount} 首",
                fontSize = 10.5.sp,
                color = Palette.Text3,
            )
        }
    }
}

@Composable
private fun Footer(
    loading: Boolean,
    error: String?,
    hasMore: Boolean,
    empty: Boolean,
    onRetry: () -> Unit,
) {
    Box(
        Modifier.fillMaxWidth().padding(vertical = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    color = Palette.Accent,
                    strokeWidth = 1.6.dp,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("加载中…", fontSize = 12.sp, color = Palette.Text3)
            }

            error != null -> Text(
                text = "$error · 点这里重试",
                fontSize = 12.sp,
                color = Palette.Text3,
                modifier = Modifier.clickable(onClick = onRetry).padding(10.dp),
            )

            empty -> Text("这个分类下暂时没有歌单", fontSize = 12.sp, color = Palette.Text3)

            !hasMore -> Text("没有更多了", fontSize = 11.5.sp, color = Palette.Text3)
        }
    }
}
