package com.weige.music

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.weige.music.data.KuwoApi
import com.weige.music.data.PlaylistApi
import com.weige.music.model.LyricLine
import com.weige.music.model.OnlinePlaylist
import com.weige.music.model.Playlist
import com.weige.music.model.PlaylistTag
import com.weige.music.model.PlaylistTagGroup
import com.weige.music.model.Quality
import com.weige.music.model.Song
import com.weige.music.play.PlayerController
import com.weige.music.script.MusicSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

class AppViewModel(app: Application) : AndroidViewModel(app) {

    // ---- 搜索 ----
    var query by mutableStateOf("")
    var results by mutableStateOf<List<Song>>(emptyList())
    var searching by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    // ---- 播放 ----
    val playState = PlayerController.state
    var lyric by mutableStateOf<List<LyricLine>>(emptyList())
        private set
    var lyricIndex by mutableStateOf(-1)
        private set

    /** 歌词还在路上。酷我缺词时要再多跑一次网易云，空窗期别急着显示「暂无歌词」 */
    var lyricLoading by mutableStateOf(false)
        private set
    var showPlayer by mutableStateOf(false)
    var recent by mutableStateOf<List<Song>>(emptyList())
        private set

    /** 夜间模式。默认关（白天），用户切换后记住 */
    var dark by mutableStateOf(false)
        private set

    /** 正在解析音源（取流可能走 JS 脚本，需要几秒） */
    var resolving by mutableStateOf(false)
        private set

    /**
     * 是否应该显示「等待音源」的转圈。
     *
     * 两个来源：本地的 [resolving]（用户刚点了播放，播放器还没来得及响应）
     * 和播放器的 buffering（服务端真正在跑脚本取流的那几秒，实际耗时都在这）。
     * 只看前者会一闪而过 —— 那正是之前「没有任何等待标识」的原因。
     */
    val waiting: Boolean
        get() = resolving || playState.value.buffering

    /** 播放队列（点搜索结果或收藏时整列表入队），以及当前位置 */
    var queue by mutableStateOf<List<Song>>(emptyList())
        private set
    var queueIndex by mutableStateOf(-1)
        private set

    /** 收藏，持久化到本地 */
    var favorites by mutableStateOf<List<Song>>(emptyList())
        private set

    /** 自建歌单 */
    var playlists by mutableStateOf<List<Playlist>>(emptyList())
        private set

    /** 当前打开的歌单详情（null 表示在歌单列表） */
    var openedPlaylistId by mutableStateOf<String?>(null)

    /** 当前打开的播放列表页签内容，随 playlists 实时变化 */
    val openedPlaylist: Playlist?
        get() = openedPlaylistId?.let { id -> playlists.firstOrNull { it.id == id } }

    /** 「加入歌单」面板要针对哪首歌弹出 */
    var addToPlaylistSong by mutableStateOf<Song?>(null)

    private var searchJob: Job? = null

    private val prefs by lazy {
        getApplication<Application>().getSharedPreferences("weige", 0)
    }

    init {
        dark = prefs.getBoolean("dark", false)
        recent = loadRecent()
        favorites = loadFavorites()
        playlists = loadPlaylists()

        // 播放器自己切歌（通知栏/耳机键/自动连播）时，同步高亮与歌词
        PlayerController.onSongChanged = { song ->
            clearResolving()
            queueIndex = queue.indexOfFirst { it.id == song.id }.takeIf { it >= 0 } ?: queueIndex
            pushRecent(song)
            loadLyric(song)
        }
        PlayerController.onEnded = { clearResolving() }

        // 启动就在后台把脚本下载好、JS 引擎提前跑起来（洛雪也是这么做的），
        // 这样第一次点歌不用再等 evaluate 那几十 KB 混淆脚本
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { MusicSource.warmUp() }
        }

        viewModelScope.launch {
            // 位置驱动歌词高亮：200ms 一次足够，没必要每帧算
            while (true) {
                val t = playState.value.positionMs / 1000f
                if (lyric.isNotEmpty()) {
                    var idx = -1
                    for (i in lyric.indices) {
                        if (lyric[i].time <= t) idx = i else break
                    }
                    if (idx != lyricIndex) lyricIndex = idx
                }
                delay(200)
            }
        }
    }

    fun onQueryChange(v: String) {
        query = v
    }

    fun search() {
        val kw = query.trim()
        if (kw.isEmpty()) return
        searchJob?.cancel()
        searching = true
        message = null
        searchJob = viewModelScope.launch {
            try {
                val list = KuwoApi.search(kw)
                results = list
                if (list.isEmpty()) message = "没搜到「$kw」"
            } catch (e: Exception) {
                message = "搜索失败：${e.message}"
                results = emptyList()
            } finally {
                searching = false
            }
        }
    }

    fun play(song: Song, fromList: List<Song>? = null) {
        // 从哪个列表点的，就把那个列表整个入队，这样上一首/下一首才顺
        val list = fromList ?: listOf(song)
        val idx = list.indexOfFirst { it.id == song.id }.let { if (it < 0) 0 else it }
        queue = list
        queueIndex = idx
        playCurrent()
    }

    private var resolvingJob: Job? = null

    /**
     * 进入「解析中」。真正取流在 PlaybackService 的 ResolvingDataSource 里，
     * 这里只是让界面立刻有反应；万一播放器没给回状态（服务还没连上等），
     * 25 秒后自动收回，不至于一直转圈。
     */
    private fun markResolving() {
        resolving = true
        resolvingJob?.cancel()
        resolvingJob = viewModelScope.launch {
            delay(25_000)
            resolving = false
        }
    }

    private fun clearResolving() {
        resolvingJob?.cancel()
        resolvingJob = null
        resolving = false
    }

    private fun playCurrent() {
        val song = queue.getOrNull(queueIndex) ?: return

        showPlayer = true
        pushRecent(song)
        markResolving()

        // 整个队列交给播放器：地址由 ResolvingDataSource 在要播的那一刻解析
        PlayerController.playQueue(queue, queueIndex, playState.value.quality)
        loadLyric(song)
    }

    private fun loadLyric(song: Song) {
        lyric = emptyList()
        lyricIndex = -1
        lyricLoading = true
        viewModelScope.launch {
            val lines = runCatching {
                withTimeoutOrNull(12_000) { KuwoApi.lyric(song) }
            }.getOrNull().orEmpty()
            if (playState.value.song?.id == song.id) {
                lyric = lines
                lyricLoading = false
            }
        }
    }

    /** 直接跳到队列里的某一首（播放列表页点选用） */
    fun playAt(index: Int) {
        if (index !in queue.indices) return
        queueIndex = index
        markResolving()
        PlayerController.playQueue(queue, index, playState.value.quality)
        loadLyric(queue[index])
    }

    fun next() {
        if (queueIndex >= queue.size - 1) {
            message = "已经是最后一首了"
            return
        }
        markResolving()
        PlayerController.next()
    }

    fun previous() {
        if (queueIndex <= 0) {
            // 已是队列开头：回到开头重播，而不是「点了没反应」
            seekTo(0)
            message = "已是队列开头，从头播放"
            return
        }
        markResolving()
        PlayerController.previous()
    }

    /* ------------------------------------------------------------ 收藏 */

    fun isFavorite(song: Song): Boolean = favorites.any { it.id == song.id }

    fun toggleFavorite(song: Song) {
        favorites = if (isFavorite(song)) {
            favorites.filterNot { it.id == song.id }
        } else {
            listOf(song) + favorites
        }
        saveFavorites()
    }

    private fun saveFavorites() {
        val arr = JSONArray()
        favorites.forEach {
            arr.put(
                JSONObject().apply {
                    put("id", it.id)
                    put("title", it.title)
                    put("artist", it.artist)
                    put("album", it.album)
                    put("duration", it.durationSec)
                    put("cover", it.coverPath)
                }
            )
        }
        prefs.edit().putString("favorites", arr.toString()).apply()
    }

    private fun loadFavorites(): List<Song> {
        val raw = prefs.getString("favorites", null) ?: return emptyList()
        return parseSongs(raw)
    }

    private fun parseSongs(raw: String): List<Song> = try {
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Song(
                id = o.optString("id"),
                title = o.optString("title"),
                artist = o.optString("artist"),
                album = o.optString("album"),
                durationSec = o.optInt("duration"),
                coverPath = o.optString("cover"),
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    /* ------------------------------------------------------------ 自建歌单 */

    fun createPlaylist(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val p = Playlist(
            id = System.currentTimeMillis().toString(),
            name = trimmed,
            songs = emptyList(),
        )
        playlists = playlists + p
        savePlaylists()
        message = "已创建歌单「$trimmed」"
    }

    fun deletePlaylist(id: String) {
        playlists = playlists.filterNot { it.id == id }
        if (openedPlaylistId == id) openedPlaylistId = null
        savePlaylists()
    }

    fun renamePlaylist(id: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        playlists = playlists.map { if (it.id == id) it.copy(name = trimmed) else it }
        savePlaylists()
    }

    /** 加歌。同一首歌在同一歌单里只留一份 */
    fun addToPlaylist(playlistId: String, song: Song) {
        var added = false
        playlists = playlists.map { p ->
            if (p.id != playlistId) return@map p
            if (p.songs.any { it.id == song.id }) return@map p
            added = true
            p.copy(songs = p.songs + song)
        }
        if (added) {
            savePlaylists()
            message = "已加入歌单"
        } else {
            message = "这首歌已经在歌单里了"
        }
        addToPlaylistSong = null
    }

    /** 从搜索结果/收藏里直接「加入歌单」，同时把它记为当前要加的歌 */
    fun pickSongForPlaylist(song: Song) {
        addToPlaylistSong = song
    }

    fun removeFromPlaylist(playlistId: String, songId: String) {
        playlists = playlists.map { p ->
            if (p.id != playlistId) p else p.copy(songs = p.songs.filterNot { it.id == songId })
        }
        savePlaylists()
    }

    private fun savePlaylists() {
        val arr = JSONArray()
        playlists.forEach { p ->
            val songs = JSONArray()
            p.songs.forEach {
                songs.put(
                    JSONObject().apply {
                        put("id", it.id)
                        put("title", it.title)
                        put("artist", it.artist)
                        put("album", it.album)
                        put("duration", it.durationSec)
                        put("cover", it.coverPath)
                    }
                )
            }
            arr.put(
                JSONObject().apply {
                    put("id", p.id)
                    put("name", p.name)
                    put("createdAt", p.createdAt)
                    put("songs", songs)
                }
            )
        }
        prefs.edit().putString("playlists", arr.toString()).apply()
    }

    private fun loadPlaylists(): List<Playlist> {
        val raw = prefs.getString("playlists", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Playlist(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    songs = parseSongs(o.optJSONArray("songs")?.toString() ?: "[]"),
                    createdAt = o.optLong("createdAt"),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /* ------------------------------------------------ 发现：在线歌单 */

    /** 标签分组（专区 / 主题 / 心情 / 场景 / 年代 / 曲风流派） */
    var tagGroups by mutableStateOf<List<PlaylistTagGroup>>(emptyList())
        private set

    /** 选中的分组下标；-1 表示「推荐」（不筛标签） */
    var selectedGroup by mutableStateOf(-1)
        private set

    var selectedTag by mutableStateOf<PlaylistTag?>(null)
        private set

    var onlinePlaylists by mutableStateOf<List<OnlinePlaylist>>(emptyList())
        private set
    var onlineLoading by mutableStateOf(false)
        private set
    var onlineHasMore by mutableStateOf(false)
        private set
    var onlineError by mutableStateOf<String?>(null)
        private set

    private var onlinePage = 1
    private var onlineJob: Job? = null
    private var tagsLoading = false

    /** 正在看的在线歌单；非 null 时整屏显示详情页 */
    var openedOnlineList by mutableStateOf<OnlinePlaylist?>(null)
        private set
    var onlineSongs by mutableStateOf<List<Song>>(emptyList())
        private set
    var onlineSongsLoading by mutableStateOf(false)
        private set

    /** 进入「发现」页时调用。标签只拉一次（失败会在下次进来时重试） */
    fun ensureDiscovery() {
        if (tagGroups.isEmpty() && !tagsLoading) {
            tagsLoading = true
            viewModelScope.launch {
                val groups = runCatching { PlaylistApi.tags() }.getOrDefault(emptyList())
                if (groups.isNotEmpty()) tagGroups = groups
                tagsLoading = false
            }
        }
        if (onlinePlaylists.isEmpty() && !onlineLoading) reloadOnline()
    }

    /** 切分组。顺手选中该组第一个标签直接出歌单，省得用户再点一次 */
    fun selectGroup(index: Int) {
        selectedGroup = index
        selectedTag = tagGroups.getOrNull(index)?.tags?.firstOrNull()
        reloadOnline()
    }

    fun selectTag(tag: PlaylistTag) {
        selectedTag = tag
        reloadOnline()
    }

    fun reloadOnline() {
        onlineJob?.cancel()
        onlinePage = 1
        onlinePlaylists = emptyList()
        onlineHasMore = false
        loadOnline(reset = true)
    }

    fun loadMoreOnline() {
        if (onlineLoading || !onlineHasMore) return
        onlinePage += 1
        loadOnline(reset = false)
    }

    private fun loadOnline(reset: Boolean) {
        val tag = selectedTag
        val page = onlinePage
        onlineJob = viewModelScope.launch {
            onlineLoading = true
            onlineError = null

            val result = runCatching {
                if (tag == null) PlaylistApi.recommend(page) else PlaylistApi.byTag(tag, page)
            }.getOrNull()

            if (result == null) {
                onlineError = "歌单没加载出来，检查下网络"
                if (!reset) onlinePage = (onlinePage - 1).coerceAtLeast(1)
                onlineLoading = false
                return@launch
            }

            onlinePlaylists = if (reset) result.list else onlinePlaylists + result.list
            onlineHasMore = result.hasMore
            onlineLoading = false
        }
    }

    fun openOnlineList(p: OnlinePlaylist) {
        openedOnlineList = p
        onlineSongs = emptyList()
        onlineSongsLoading = true
        viewModelScope.launch {
            // 把名字一起传进去：专区里有几个空壳歌单（服务端 musicCount=0），
            // 靠名字找同类歌单顶上，否则点进去是空白
            val d = runCatching { PlaylistApi.detail(p.id, p.name) }.getOrNull()
            if (openedOnlineList?.id != p.id) return@launch    // 已经切走了

            onlineSongs = d?.songs.orEmpty()
            if (d != null && d.info.name.isNotBlank()) {
                openedOnlineList = d.info
                if (d.info.id != p.id) {
                    message = "《${p.name}》暂无内容，已换成《${d.info.name}》"
                }
            } else if (onlineSongs.isEmpty()) {
                message = "这个歌单暂时取不到内容"
            }
            onlineSongsLoading = false
        }
    }

    fun closeOnlineList() {
        openedOnlineList = null
        onlineSongs = emptyList()
    }

    fun toggle() = PlayerController.toggle()

    fun seekTo(ms: Long) = PlayerController.seekTo(ms)

    /** 换档位要重新解析地址，所以整首重放一次 */
    fun setQuality(q: Quality) {
        PlayerController.setQuality(q)
        playState.value.song?.let { play(it) }
    }

    fun dismissMessage() {
        message = null
    }

    fun toggleTheme() {
        dark = !dark
        prefs.edit().putBoolean("dark", dark).apply()
    }

    // ---- 最近播放（本地记录，不上传）----

    private fun pushRecent(song: Song) {
        val next = (listOf(song) + recent.filter { it.id != song.id }).take(20)
        recent = next
        val arr = JSONArray()
        next.forEach {
            arr.put(
                JSONObject().apply {
                    put("id", it.id)
                    put("title", it.title)
                    put("artist", it.artist)
                    put("album", it.album)
                    put("duration", it.durationSec)
                    put("cover", it.coverPath)
                }
            )
        }
        prefs.edit().putString("recent", arr.toString()).apply()
    }

    private fun loadRecent(): List<Song> {
        val raw = prefs.getString("recent", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Song(
                    id = o.optString("id"),
                    title = o.optString("title"),
                    artist = o.optString("artist"),
                    album = o.optString("album"),
                    durationSec = o.optInt("duration"),
                    coverPath = o.optString("cover"),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
