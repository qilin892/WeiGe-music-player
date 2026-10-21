package com.weige.music.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import com.weige.music.data.KuwoApi
import com.weige.music.model.Song
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** 品牌标记：粗笔画 W，和 PC 端 favicon 同一形状 */
@Composable
fun WMark(modifier: Modifier = Modifier, color: Color = Palette.Accent) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.14f
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.12f, h * 0.22f)
            lineTo(w * 0.30f, h * 0.78f)
            lineTo(w * 0.50f, h * 0.42f)
            lineTo(w * 0.70f, h * 0.78f)
            lineTo(w * 0.88f, h * 0.22f)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = stroke, cap = StrokeCap.Square)
        )
    }
}

/**
 * 封面。
 *
 * W 占位**一直铺在底层**：加载中露出来当占位，加载成功被图片盖住，
 * 加载失败（404 / 域名不通）也不会留一块空白 —— 这正是之前的毛病：
 * 只在 url 为空时才画 W，URL 有了但请求挂了就只剩灰块。
 */
@Composable
fun CoverArt(
    song: Song?,
    size: Dp,
    radius: Dp = 8.dp,
    modifier: Modifier = Modifier,
    hd: Boolean = false,
) {
    val shape = RoundedCornerShape(radius)
    val url = song?.let { KuwoApi.coverUrl(it, if (hd) 800 else 500) }.orEmpty()
    var failed by remember(url) { mutableStateOf(false) }

    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(Palette.Panel2)
    ) {
        WMark(
            modifier = Modifier.fillMaxSize().padding(size * 0.22f),
            color = Palette.AccentDim,
        )
        if (url.isNotEmpty() && !failed) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                onState = { state ->
                    if (state is AsyncImagePainter.State.Error) failed = true
                },
            )
        }
    }
}

/**
 * VU 电平：播放时随机跳动，暂停时压成一条低电平。
 * 纯装饰，不做真实音频分析（对听感没影响，还省电）。
 */
@Composable
fun VuMeter(
    playing: Boolean,
    modifier: Modifier = Modifier,
    bars: Int = 22,
    activeColor: Color = Palette.Accent,
    idleColor: Color = Palette.Line,
) {
    val transition = rememberInfiniteTransition(label = "vu")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing)),
        label = "phase",
    )

    Canvas(modifier) {
        if (bars <= 0) return@Canvas
        val slot = size.width / bars
        val barW = slot * 0.45f
        for (i in 0 until bars) {
            val level = if (playing) {
                val p = phase + i * 0.55f
                0.18f + 0.82f * abs(sin(p)) * (0.55f + 0.45f * sin(i * 1.7f).toFloat().coerceAtLeast(0f))
            } else {
                0.08f
            }
            val h = (size.height * level).coerceAtLeast(1.5f)
            drawRect(
                color = if (playing) activeColor.copy(alpha = 0.35f + 0.65f * level) else idleColor,
                topLeft = Offset(i * slot + (slot - barW) / 2f, size.height - h),
                size = Size(barW, h),
            )
        }
    }
}

/** 细进度条，未播放部分用暗线 */
@Composable
fun ThinProgress(progress: Float, modifier: Modifier = Modifier) {
    // Canvas 的 draw lambda 不是 composable 作用域，颜色要先取出来
    val track = Palette.Line
    val fill = Palette.Accent

    Canvas(modifier) {
        val h = size.height.coerceAtLeast(1f)
        drawRect(color = track, topLeft = Offset(0f, 0f), size = Size(size.width, h))
        drawRect(
            color = fill,
            topLeft = Offset(0f, 0f),
            size = Size(size.width * progress.coerceIn(0f, 1f), h),
        )
    }
}

/** 居中空状态提示 */
@Composable
fun Hint(text: String, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        androidx.compose.material3.Text(
            text = text,
            color = Palette.Text3,
            fontSize = 13.sp,
        )
    }
}
