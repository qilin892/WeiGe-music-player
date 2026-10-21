package com.weige.music.play

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.weige.music.model.Quality
import com.weige.music.script.MusicSource
import kotlinx.coroutines.runBlocking

/**
 * 后台播放服务。
 *
 * 用 MediaSessionService 而不是裸 Service：通知栏控制、锁屏控制、耳机按键都由 Media3 接管。
 *
 * 这里的关键是 [ResolvingDataSource]：队列里的 MediaItem 只带 `weige://<songId>?q=<level>`，
 * 播放器要读某首时才回调进来，现场跑音源脚本换成真实 http 地址。
 * 这样既能给通知栏一个「完整队列」（于是它会画出上一首/下一首），又不用提前解析所有地址。
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()

        val baseFactory = DefaultDataSource.Factory(this, DefaultHttpDataSource.Factory())

        val resolvingFactory = ResolvingDataSource.Factory(baseFactory) { dataSpec ->
            val uri = dataSpec.uri
            if (uri.scheme != SCHEME) return@Factory dataSpec

            val songId = uri.host.orEmpty()
            val quality = Quality.of(uri.getQueryParameter("q"))
            val song = SongRegistry.get(songId)

            if (song == null) {
                Log.w(TAG, "队列里找不到 $songId，无法解析")
                return@Factory dataSpec
            }

            // 会跑 JS 脚本，可能几秒；这里在播放器的加载线程上，阻塞可以接受
            val real = runBlocking { MusicSource.url(song, quality) }
            if (real.isNullOrBlank()) {
                Log.w(TAG, "取流失败：${song.title}")
                return@Factory dataSpec
            }
            Log.i(TAG, "解析到直链：${song.title} → ${real.take(60)}")
            dataSpec.withUri(Uri.parse(real))
        }

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(resolvingFactory))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        mediaSession = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    private companion object {
        const val TAG = "PlaybackService"
        const val SCHEME = "weige"
    }
}
