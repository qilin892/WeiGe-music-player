package com.weige.music.play

import android.app.Notification
import android.content.Context
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaStyleNotificationHelper
import com.google.common.collect.ImmutableList

/**
 * 播放通知的自定义 Provider。
 *
 * 为什么要自己实现：默认 Provider 的文案全部来自 `player.mediaMetadata`，
 * 想让它显示当前歌词就只能 `replaceMediaItem` 改 metadata ——
 * 而那会导致播放器重建媒体源，每句歌词重新解析一次直链，进度条跟着抖。
 *
 * 这里改成「通知自己读 [NowPlayingState]」，播放器的 metadata 只在切歌时设一次，
 * 歌词和封面都在外部更新，然后 [refreshIfNeeded] 刷一下通知，播放侧零打扰。
 */
class WeigeNotificationProvider(private val context: Context) : MediaNotification.Provider {

    private var session: MediaSession? = null
    private var factory: MediaNotification.ActionFactory? = null
    private var lastSignature: String = ""

    override fun createNotification(
        mediaSession: MediaSession,
        customLayout: ImmutableList<androidx.media3.session.CommandButton>,
        actionFactory: MediaNotification.ActionFactory,
        onNotificationChangedCallback: MediaNotification.Provider.Callback,
    ): MediaNotification {
        session = mediaSession
        factory = actionFactory
        lastSignature = signature()
        return MediaNotification(NOTIFICATION_ID, build())
    }

    override fun handleCustomCommand(
        session: MediaSession,
        action: String,
        extras: Bundle,
    ): Boolean = false

    /**
     * 歌词/封面/播放状态变了就重建通知。签名没变直接返回，
     * 避免无谓的 notify（每 200ms 一次的话系统媒体面板会抖）。
     */
    fun refreshIfNeeded() {
        val next = signature()
        if (next == lastSignature) return
        val n = runCatching { build() }.getOrNull() ?: return
        lastSignature = next
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, n) }
    }

    private fun signature(): String {
        val p = session?.player ?: return ""
        val id = p.currentMediaItem?.mediaId
        return "$id|${p.isPlaying}|${NowPlayingState.lyricLine}|${NowPlayingState.coverOf(id) != null}"
    }

    private fun build(): Notification {
        val s = session ?: error("notification session 还没就绪")
        val p = s.player
        val meta = p.mediaMetadata
        val songId = p.currentMediaItem?.mediaId
        val playing = p.isPlaying

        val title = meta.title?.toString().orEmpty().ifBlank { "伟哥音乐" }
        val artist = meta.artist?.toString().orEmpty()
        val lyric = NowPlayingState.lyricLine
        val text = if (lyric.isBlank()) artist else lyric

        val builder = NotificationCompat.Builder(context, PlaybackService.CHANNEL_ID)
            .setSmallIcon(androidx.media3.session.R.drawable.media3_notification_small_icon)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(s.sessionActivity)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setShowWhen(false)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setOngoing(playing)

        NowPlayingState.coverOf(songId)?.let { builder.setLargeIcon(it) }
        if (lyric.isNotBlank() && artist.isNotBlank()) builder.setSubText(artist)

        val f = factory ?: error("action factory 还没就绪")

        // ActionFactory.createMediaAction(MediaSession, IconCompat, title, command)
        val prev = androidx.media3.session.R.drawable.media3_icon_previous
        val playIcon = if (playing) {
            androidx.media3.session.R.drawable.media3_icon_pause
        } else {
            androidx.media3.session.R.drawable.media3_icon_play
        }
        val next = androidx.media3.session.R.drawable.media3_icon_next

        builder.addAction(
            f.createMediaAction(
                s,
                IconCompat.createWithResource(context, prev),
                "上一首",
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM.toInt(),
            )
        )
        builder.addAction(
            f.createMediaAction(
                s,
                IconCompat.createWithResource(context, playIcon),
                if (playing) "暂停" else "播放",
                Player.COMMAND_PLAY_PAUSE.toInt(),
            )
        )
        builder.addAction(
            f.createMediaAction(
                s,
                IconCompat.createWithResource(context, next),
                "下一首",
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM.toInt(),
            )
        )

        builder.setStyle(
            MediaStyleNotificationHelper.MediaStyle(s).setShowActionsInCompactView(0, 1, 2)
        )

        // 再盖一层自定义展开视图：MediaStyle 那个展开态没有大封面，
        // 这里给「大封面 + 歌名 + 歌词 + 三个按钮」。Style 仍保留，系统照旧认它是媒体通知。
        runCatching { builder.setCustomBigContentView(buildExpanded(s, f, title, text, songId, playing)) }

        return builder.build()
    }

    private fun buildExpanded(
        session: MediaSession,
        factory: MediaNotification.ActionFactory,
        title: String,
        lyricText: String,
        songId: String?,
        playing: Boolean,
    ): android.widget.RemoteViews {
        val rv = android.widget.RemoteViews(context.packageName, com.weige.music.R.layout.notification_player)
        rv.setTextViewText(com.weige.music.R.id.np_title, title)
        rv.setTextViewText(com.weige.music.R.id.np_lyric, lyricText)

        val prevIcon = androidx.media3.session.R.drawable.media3_icon_previous
        val playIcon = if (playing) {
            androidx.media3.session.R.drawable.media3_icon_pause
        } else {
            androidx.media3.session.R.drawable.media3_icon_play
        }
        val nextIcon = androidx.media3.session.R.drawable.media3_icon_next

        rv.setImageViewResource(com.weige.music.R.id.np_prev, prevIcon)
        rv.setImageViewResource(com.weige.music.R.id.np_play, playIcon)
        rv.setImageViewResource(com.weige.music.R.id.np_next, nextIcon)

        NowPlayingState.coverOf(songId)?.let {
            rv.setImageViewBitmap(com.weige.music.R.id.np_cover, it)
        }

        rv.setOnClickPendingIntent(
            com.weige.music.R.id.np_prev,
            factory.createMediaActionPendingIntent(session, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM.toLong()),
        )
        rv.setOnClickPendingIntent(
            com.weige.music.R.id.np_play,
            factory.createMediaActionPendingIntent(session, Player.COMMAND_PLAY_PAUSE.toLong()),
        )
        rv.setOnClickPendingIntent(
            com.weige.music.R.id.np_next,
            factory.createMediaActionPendingIntent(session, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM.toLong()),
        )
        return rv
    }

    private companion object {
        const val NOTIFICATION_ID = 1001
    }
}
