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

/** 播放器对外暴露的状态，UI 只读这个 */
data class PlayState(
    val song: Song? = null,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val quality: Quality = Quality.HIGH,
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

    fun connect(context: Context) {
        if (controller != null) return

        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()

        future.addListener({
            val c = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = c
            c.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    _state.value = _state.value.copy(playing = isPlaying)
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    val id = mediaItem?.mediaId ?: return
                    val song = SongRegistry.get(id) ?: return
                    _state.value = _state.value.copy(
                        song = song,
                        positionMs = 0,
                        durationMs = song.durationSec * 1000L,
                        buffering = true,
                        error = null,
                    )
                    onSongChanged?.invoke(song)
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        _state.value = _state.value.copy(playing = false, buffering = false)
                        onEnded?.invoke()
                        return
                    }
                    _state.value = _state.value.copy(
                        buffering = playbackState == Player.STATE_BUFFERING,
                        durationMs = c.duration.coerceAtLeast(0),
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
                    if (c.isPlaying || c.playbackState == Player.STATE_BUFFERING) {
                        _state.value = _state.value.copy(
                            positionMs = c.currentPosition.coerceAtLeast(0),
                            durationMs = c.duration.coerceAtLeast(0),
                        )
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
