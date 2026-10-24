package com.weige.music.play

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.weige.music.R
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

        // 点通知栏/锁屏 → 回到 App 的播放页，而不是只把 App 拉到前台
        val launch = Intent(this, com.weige.music.MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_PLAYER, true)
        }
        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity)
            .build()

        // 注意：这个方法在 MediaSessionService 上，不在 MediaSession.Builder 上
        ensureChannel()
        val provider = WeigeNotificationProvider(this)
        setMediaNotificationProvider(provider)
        // 歌词/封面变化时由这里刷新通知（不经过播放器，所以不会触发重新解析）
        NowPlayingState.onChanged = { provider.refreshIfNeeded() }
    }

    /**
     * 自己建播放通知的 channel。
     *
     * 两个坑：
     *  1. importance 用 LOW 会被系统归进「静默通知」分组（dumpsys 里 groupKey=silent），
     *     **静默通知在锁屏上直接不显示** —— 这就是锁屏一直空着的原因。改成 DEFAULT，
     *     再用 setSound(null) 保持不响。
     *  2. channel 的 lockscreenVisibility 一经创建就改不动（旧渠道实测一直是 -1000
     *     NO_OVERRIDE），所以换了个新 id 重建，顺便删掉旧的。
     *
     * 另外还有一层用户开关：设置 → 通知 → 伟哥音乐 → 锁屏通知，那个关掉的话代码也白搭。
     */
    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        runCatching { manager.deleteNotificationChannel("weige_playback") }

        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_playback),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = getString(R.string.notif_channel_playback_desc)
            setShowBadge(false)
            // 要的是「可见但不打扰」：DEFAULT 拿到锁屏可见性，声音和振动单独关掉
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        NowPlayingState.onChanged = null
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    companion object {
        const val TAG = "PlaybackService"
        const val SCHEME = "weige"
        const val CHANNEL_ID = "weige_playback_v2"

        /** 通知栏点进来时带这个 extra，MainActivity 据此直接打开播放页 */
        const val EXTRA_OPEN_PLAYER = "openPlayer"
    }
}
