package com.weige.music.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import com.weige.music.model.Quality
import com.weige.music.model.Song
import com.weige.music.script.MusicSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 下载与分享。
 *
 * 保存位置分两条路：
 *   Android 10+  →  MediaStore（`音乐/WeigeMusic/`，文件管理器和其他播放器都能看到，**不需要权限**）
 *   Android 8/9  →  app 私有目录（走 FileProvider 分享，同样不需要权限）
 * 之所以不统一用公共目录：API 29 以下写公共目录要 WRITE_EXTERNAL_STORAGE 运行时权限，
 * 为一个下载功能加权限弹窗不划算。
 */
object Downloader {

    private const val TAG = "Downloader"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
    private const val AUTHORITY = "com.weige.music.fileprovider"
    private const val FOLDER = "WeigeMusic"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** 下载结果：可以直接拿 [uri] 去分享 */
    data class Saved(val uri: Uri, val name: String)

    /**
     * 解析直链并落盘。
     *
     * @param onProgress 0..100；直链没给 Content-Length 时不会回调
     */
    suspend fun save(
        context: Context,
        song: Song,
        quality: Quality,
        onProgress: (Int) -> Unit,
    ): Saved = withContext(Dispatchers.IO) {
        val url = MusicSource.url(song, quality)
            ?: throw IOException("解析不到音源地址")

        val ext = guessExt(url)
        val name = "${safeName(song.artist)} - ${safeName(song.title)}.$ext"
        val mime = mimeOf(ext)

        val req = Request.Builder().url(url).header("User-Agent", UA).build()
        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw IOException("HTTP ${res.code}")
            val body = res.body ?: throw IOException("空响应")
            val total = body.contentLength()
            val input = body.byteStream()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                writeMediaStore(context, name, mime, input, total, onProgress)
            } else {
                writePrivate(context, name, input, total, onProgress)
            }
        }
    }

    /** Android 10+：进系统媒体库，卸载 App 也不会丢 */
    private fun writeMediaStore(
        context: Context,
        name: String,
        mime: String,
        input: java.io.InputStream,
        total: Long,
        onProgress: (Int) -> Unit,
    ): Saved {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, name)
            put(MediaStore.Audio.Media.MIME_TYPE, mime)
            put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/$FOLDER")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("媒体库拒绝创建文件")

        try {
            resolver.openOutputStream(uri)?.use { out ->
                copyWithProgress(input, out, total, onProgress)
            } ?: throw IOException("媒体库打开输出流失败")

            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) },
                null,
                null,
            )
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        Log.i(TAG, "已保存到媒体库：$name")
        return Saved(uri, name)
    }

    /** Android 8/9：私有目录 + FileProvider */
    private fun writePrivate(
        context: Context,
        name: String,
        input: java.io.InputStream,
        total: Long,
        onProgress: (Int) -> Unit,
    ): Saved {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), FOLDER)
        if (!dir.exists() && !dir.mkdirs()) throw IOException("无法创建目录")
        val file = File(dir, name)

        try {
            file.outputStream().use { out -> copyWithProgress(input, out, total, onProgress) }
        } catch (e: Exception) {
            runCatching { file.delete() }
            throw e
        }

        val uri = FileProvider.getUriForFile(context, AUTHORITY, file)
        Log.i(TAG, "已保存到私有目录：${file.absolutePath}")
        return Saved(uri, name)
    }

    private fun copyWithProgress(
        input: java.io.InputStream,
        out: java.io.OutputStream,
        total: Long,
        onProgress: (Int) -> Unit,
    ) {
        val buf = ByteArray(64 * 1024)
        var done = 0L
        var lastPct = -1
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            done += n
            if (total > 0) {
                val pct = (done * 100 / total).toInt().coerceIn(0, 100)
                if (pct != lastPct) {
                    lastPct = pct
                    onProgress(pct)
                }
            }
        }
        out.flush()
    }

    /** 调系统分享面板。content:// 的读权限靠 FLAG_GRANT_READ_URI_PERMISSION 传出去 */
    fun share(context: Context, saved: Saved, title: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeOf(saved.name.substringAfterLast('.', "mp3"))
            putExtra(Intent.EXTRA_STREAM, saved.uri)
            putExtra(Intent.EXTRA_SUBJECT, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(send, "分享「$title」")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }

    /** 从直链里认后缀，认不出就当 mp3（源里绝大多数是 mp3） */
    private fun guessExt(url: String): String {
        val path = url.substringBefore('?').lowercase()
        return when {
            path.endsWith(".flac") -> "flac"
            path.endsWith(".m4a") -> "m4a"
            path.endsWith(".aac") -> "aac"
            path.endsWith(".ogg") || path.endsWith(".opus") -> "ogg"
            path.endsWith(".wav") -> "wav"
            else -> "mp3"
        }
    }

    private fun mimeOf(ext: String): String = when (ext.lowercase()) {
        "flac" -> "audio/flac"
        "m4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        "ogg" -> "audio/ogg"
        "wav" -> "audio/wav"
        else -> "audio/mpeg"
    }

    /** 文件名里不能带路径分隔符和 Windows 那批保留字符 */
    private fun safeName(raw: String): String = raw
        .replace(Regex("""[\\/:*?"<>|\r\n\t]"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
        .take(60)
        .ifBlank { "未知" }
}
