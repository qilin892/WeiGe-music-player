package com.weige.music.play

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.weige.music.data.KuwoApi
import com.weige.music.model.PlayMode
import com.weige.music.model.Quality
import com.weige.music.model.Song
import com.weige.music.script.MusicSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** 播放器对外暴露的状态，UI 只读这个 */
data class PlayState(
    val song: Song? = null,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val quality: Quality = Quality.HIGH,
    val playMode: PlayMode = PlayMode.SEQUENCE,
    val error: String? = null,
)

/**
 * MediaController 的轻封装。
 *
 * MediaController 是异步连上 Service 的，所以对外只暴露 StateFlow，
 * UI 不需要知道连接时机。
 */
object PlayerController {

    private var controller: MediaController? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 一曲播完的回调，由 ViewModel 接上「自动下一首」 */
    var onEnded: (() -> Unit)? = null

    /** 播放器自己切歌（通知栏、耳机键、自动连播）时回调，ViewModel 用它同步歌词与高亮 */
    var onSongChanged: ((Song) -> Unit)? = null

    private val _state = MutableStateFlow(PlayState())
    val state: StateFlow<PlayState> = _state.asStateFlow()

    private var ticker: Boolean = false

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** 已经取过封面的 songId，避免来回切歌重复下载 */
    private val artworkRequested = mutableSetOf<String>()

    /** 当前播放模式；controller 还没连上时先记住，连上再应用 */
    private var pendingMode: PlayMode = PlayMode.SEQUENCE

    fun connect(context: Context) {
        if (controller != null) return

        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()

        future.addListener({
            val c = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = c
            c.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    // 已经在出声了就一定不在缓冲，顺手清掉缓冲标记，
                    // 免得某个回调漏了导致转圈停不下来
                    _state.value = _state.value.copy(
                        playing = isPlaying,
                        buffering = if (isPlaying) false else _state.value.buffering,
                    )
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    val id = mediaItem?.mediaId ?: return
                    val song = SongRegistry.get(id) ?: return
                    // 这里**不能**写死 buffering = true。
                    // 自动连播下一首时，ExoPlayer 往往已经把它缓冲好了，切过去仍是 READY，
                    // 不会再回调 onPlaybackStateChanged，写死 true 就没人改回来 ——
                    // 表现就是歌已经在放了，「正在解析音源」还在转。
                    _state.value = _state.value.copy(
                        song = song,
                        positionMs = 0,
                        // 曲目信息里的时长才可靠；拿不到就沿用旧值，别写 0
                        durationMs = if (song.durationSec > 0) {
                            song.durationSec * 1000L
                        } else {
                            _state.value.durationMs
                        },
                        buffering = c.playbackState == Player.STATE_BUFFERING,
                        error = null,
                    )
                    onSongChanged?.invoke(song)
                    // 新歌歌词还没到，先把通知上那句旧的清掉
                    NowPlayingState.setLyric("")
                    prefetchArtwork(song)
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        _state.value = _state.value.copy(playing = false, buffering = false)
                        onEnded?.invoke()
                        return
                    }
                    // 注意 c.duration：媒体源刚重建的那一瞬是 C.TIME_UNSET（负数），
                    // 以前直接 coerceAtLeast(0) 会写成 0 → 进度条拿 0 当时长，
                    // value 被 coerceIn 拉满，看起来就是「播放条闪一下」。
                    // 所以只在有效时才写，否则保留上一次的值。
                    val d = c.duration
                    _state.value = _state.value.copy(
                        buffering = playbackState == Player.STATE_BUFFERING,
                        durationMs = if (d > 0) d else _state.value.durationMs,
                    )
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    // 直链多半过期了：作废缓存，下次点这首歌重新解析
                    _state.value.song?.let {
                        MusicSource.invalidate(it, _state.value.quality)
                    }
                    _state.value = _state.value.copy(
                        error = "播放失败：${error.errorCodeName}",
                        buffering = false,
                        playing = false,
                    )
                }
            })

            _state.value = _state.value.copy(playing = c.isPlaying)
            applyPlayMode()
            startTicker()
        }, ContextCompat.getMainExecutor(context))
    }

    /** 位置要自己轮询，Player 没有位置变化的回调 */
    private fun startTicker() {
        if (ticker) return
        ticker = true
        scope.launch {
            while (ticker) {
                controller?.let { c ->
                    val pb = c.playbackState
                    val buffering = pb == Player.STATE_BUFFERING
                    if (c.isPlaying || buffering) {
                        // 同样的问题：位置和时长都可能短暂是 TIME_UNSET，无效就沿用旧值
                        val pos = c.currentPosition
                        val d = c.duration
                        _state.value = _state.value.copy(
                            positionMs = if (pos >= 0) pos else _state.value.positionMs,
                            durationMs = if (d > 0) d else _state.value.durationMs,
                        )
                    }
                    // 兜底校正：回调偶尔会丢，卡住的转圈比不转还难受。
                    // 不一致才写，避免每 200ms 都触发一次重组。
                    if (_state.value.buffering != buffering) {
                        _state.value = _state.value.copy(buffering = buffering)
                    }
                }
                delay(200)
            }
        }
    }

    /**
     * 播放一首歌。url 由调用方先解析好（走音源脚本，可能耗时几秒），
     * 这里只管把地址塞给 ExoPlayer。
     */
    /**
     * 播整个队列。
     *
     * 不再「一首一首塞」—— 只有把完整队列交给播放器，通知栏才会出现上一首/下一首。
     * 每首的 URI 只是一个标识（`weige://id?q=level`），真实地址由 PlaybackService 里的
     * ResolvingDataSource 在要播的那一刻解析。
     */
    fun playQueue(list: List<Song>, index: Int, quality: Quality) {
        val c = controller ?: return
        if (list.isEmpty()) return

        val idx = index.coerceIn(0, list.size - 1)
        SongRegistry.putAll(list)

        val items = list.map { song ->
            // 通知栏/锁屏用大图，800 比 120 那版清爽得多
            val cover = KuwoApi.coverUrl(song, 800)
            MediaItem.Builder()
                .setUri("weige://${song.id}?q=${quality.level}")
                .setMediaId(song.id)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(song.title)
                        .setArtist(song.artist)
                        .setAlbumTitle(song.album)
                        .apply { if (cover.isNotEmpty()) setArtworkUri(Uri.parse(cover)) }
                        .build()
                )
                .build()
        }

        val current = list[idx]
        _state.value = _state.value.copy(
            song = current,
            quality = quality,
            positionMs = 0,
            durationMs = current.durationSec * 1000L,
            error = null,
            buffering = true,
        )

        c.setMediaItems(items, idx, 0)
        c.prepare()
        c.play()
        // setMediaItems 之后重新压一遍模式：随机/循环要落到新的队列上
        applyPlayMode()
    }

    /**
     * 切换播放模式。
     *
     * 随机播放同时把 repeatMode 设成 ALL —— 只开 shuffle 的话，
     * 摇到随机序列最后一首还是会停，和用户对「随机播放」的预期不符。
     */
    fun setPlayMode(mode: PlayMode) {
        pendingMode = mode
        _state.value = _state.value.copy(playMode = mode)
        applyPlayMode()
    }

    private fun applyPlayMode() {
        val c = controller ?: return
        when (pendingMode) {
            PlayMode.SEQUENCE -> {
                c.shuffleModeEnabled = false
                c.repeatMode = Player.REPEAT_MODE_OFF
            }

            PlayMode.LOOP_ONE -> {
                c.shuffleModeEnabled = false
                c.repeatMode = Player.REPEAT_MODE_ONE
            }

            PlayMode.SHUFFLE -> {
                c.repeatMode = Player.REPEAT_MODE_ALL
                c.shuffleModeEnabled = true
            }
        }
    }

    /**
     * 预取封面，交给通知栏用。
     *
     * 以前的做法是把字节塞进 MediaMetadata.artworkData，但那必须走 `replaceMediaItem`，
     * 会让播放器**重建媒体源**——重新跑一遍音源解析，进度条跟着抖。
     * 现在只放进 [NowPlayingState]，由通知 Provider 自己取，播放侧完全不受影响。
     */
    fun prefetchArtwork(song: Song) {
        if (!artworkRequested.add(song.id)) return
        scope.launch(Dispatchers.IO) {
            val url = KuwoApi.coverUrl(song, 800)
            if (url.isEmpty()) return@launch
            val raw = runCatching {
                http.newCall(Request.Builder().url(url).build()).execute().use { it.body?.bytes() }
            }.getOrNull() ?: return@launch
            val bmp = runCatching { decodeScaled(raw, 512) }.getOrNull() ?: return@launch
            withContext(Dispatchers.Main) {
                NowPlayingState.setCover(song.id, bmp)
                NowPlayingState.onChanged?.invoke()
            }
        }
    }

    /** 800 宽的原图直接当通知大图太占内存，缩到 512 边足够清晰 */
    private fun decodeScaled(raw: ByteArray, maxSide: Int): android.graphics.Bitmap? {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null

        var sample = 1
        while (longest / sample > maxSide * 2) sample *= 2

        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size, opts)
            ?: return null
        if (maxOf(bmp.width, bmp.height) <= maxSide) return bmp

        val ratio = maxSide.toFloat() / maxOf(bmp.width, bmp.height)
        return android.graphics.Bitmap.createScaledBitmap(
            bmp,
            (bmp.width * ratio).toInt().coerceAtLeast(1),
            (bmp.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
    }

    fun toggle() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun next() {
        controller?.seekToNextMediaItem()
    }

    fun previous() {
        controller?.seekToPreviousMediaItem()
    }

    /**
     * 有没有下一首/上一首。
     *
     * 必须问播放器而不是拿 ViewModel 的 queueIndex 判断：
     * 随机模式下播放顺序和队列下标不是一回事，用下标判断会误报「已经是最后一首」。
     * 循环模式下这两个值恒为 true，也就自然不会有边界提示。
     */
    fun hasNext(): Boolean = controller?.hasNextMediaItem() ?: false

    fun hasPrevious(): Boolean = controller?.hasPreviousMediaItem() ?: false

    fun seekTo(ms: Long) {
        controller?.seekTo(ms.coerceAtLeast(0))
    }

    /** 只记档位。真要换档重播由 ViewModel 重新入队后再调 playQueue() */
    fun setQuality(q: Quality) {
        _state.value = _state.value.copy(quality = q)
    }

    fun notifyError(text: String) {
        _state.value = _state.value.copy(error = text, buffering = false)
    }
}
