package com.chinut.yanhuo.data

import com.chinut.yanhuo.core.Diag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 收藏 / 订阅 / 观看进度 的本地库。
 *
 * # 为什么放进一个文件而不是三个
 *
 * 三者都要"改了界面立刻变"（`StateFlow`），也都要"改了立刻落盘"。
 * 分成三个对象就要三套 mutex + 三份样板代码，收益为零。
 * 所以内部按 [Kind] 分成三张表，存进同一个 TSV（用 kind 列区分）。
 *
 * # 每条都带 [Row.title] / [Row.cover]
 *
 * 收藏页和订阅页**不希望再联网取一遍标题和封面**（那样打开会转圈）。
 * 存下这两个字段，列表页就能纯本地渲染。
 */
object UserStore {

    private const val TAG = "UserStore"
    private const val FILE = "user_data.tsv"

    enum class Kind { FAVORITE, SUBSCRIBE, PROGRESS }

    /**
     * 一条记录。
     *
     * 用**一张扁平表**承载三种语义：好处是加字段只改一处，
     * 且三种数据在同一文件里原子写入（不会出现"收藏写成功、进度写一半"）。
     */
    data class Row(
        val kind: Kind,
        /** 作品 key（`sourceId:targetId`），见 [Drama.key]。 */
        val key: String,
        val title: String = "",
        val cover: String = "",
        /** 收藏/订阅时**已知的总集数** —— 订阅更新检测就拿它做基线。 */
        val knownEpisodes: Int = 0,
        /** 观看进度：看到第几集（1 起）。 */
        val episodeIndex: Int = 0,
        val episodeName: String = "",
        /** 观看进度：毫秒。 */
        val positionMs: Long = 0L,
        val durationMs: Long = 0L,
        val updatedAt: Long = 0L,
        /** 最近一次检查订阅时的集数（用于提示"已更新到 N 集"）。 */
        val checkedEpisodes: Int = 0,
    ) {
        /** 播放进度 0~1。 */
        val progress: Float
            get() = if (durationMs <= 0L) 0f
            else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)

        /** 已看完（结尾 20 秒内算看完）。 */
        val finished: Boolean
            get() = durationMs > 0L && positionMs >= durationMs - 20_000L

        /**
         * 订阅是否有新集。
         *
         * 两个来源：已经检查出来的 [checkedEpisodes]，或者本地片库已知的集数
         * 已经超过订阅时的基线。后者让"刚打开 App 还没检查"也能立刻看到红点。
         */
        val hasUpdate: Boolean
            get() = kind == Kind.SUBSCRIBE && checkedEpisodes > knownEpisodes
    }

    private val mutex = Mutex()

    private val _rows = MutableStateFlow<List<Row>>(emptyList())

    /** 全部记录。界面订阅它，改动后自动重组。 */
    val rows: StateFlow<List<Row>> = _rows.asStateFlow()

    @Volatile
    private var loaded = false

    // ==================== 读 ====================

    /** 首次读取（幂等，多次调用只读一次磁盘）。 */
    suspend fun ensureLoaded() = withContext(Dispatchers.IO) {
        if (loaded) return@withContext
        mutex.withLock {
            if (loaded) return@withLock
            val list = TsvFile.read(FILE).mapNotNull(::parse)
            _rows.value = list
            loaded = true
            Diag.info(TAG, "本地数据读取完成：${list.size} 条")
        }
    }

    /** 重新从磁盘读（导入/恢复后调用）。 */
    suspend fun reload() = withContext(Dispatchers.IO) {
        mutex.withLock {
            _rows.value = TsvFile.read(FILE).mapNotNull(::parse)
            loaded = true
        }
    }

    fun favorites(): List<Row> =
        _rows.value.filter { it.kind == Kind.FAVORITE }.sortedByDescending { it.updatedAt }

    fun subscribes(): List<Row> =
        _rows.value.filter { it.kind == Kind.SUBSCRIBE }.sortedByDescending { it.updatedAt }

    /** 有更新的订阅数量（首页红点 / 底部导航角标用）。 */
    fun updateCount(): Int = _rows.value.count { it.hasUpdate }

    fun isFavorite(key: String): Boolean =
        _rows.value.any { it.kind == Kind.FAVORITE && it.key == key }

    fun isSubscribed(key: String): Boolean =
        _rows.value.any { it.kind == Kind.SUBSCRIBE && it.key == key }

    fun progress(key: String): Row? =
        _rows.value.firstOrNull { it.kind == Kind.PROGRESS && it.key == key }

    /**
     * 「继续观看」：有进度、没看完、且看够了一点点。
     *
     * 为什么要 [MIN_POSITION_MS]：刚点开就退出的记录毫无意义，
     * 留在首页只会把真正想看的挤下去。
     */
    fun continueWatching(limit: Int = 12): List<Row> =
        _rows.value
            .filter { it.kind == Kind.PROGRESS && !it.finished && it.positionMs >= MIN_POSITION_MS }
            .sortedByDescending { it.updatedAt }
            .take(limit)

    fun history(limit: Int = 100): List<Row> =
        _rows.value
            .filter { it.kind == Kind.PROGRESS }
            .sortedByDescending { it.updatedAt }
            .take(limit)

    // ==================== 写 ====================

    suspend fun toggleFavorite(drama: Drama) = withContext(Dispatchers.IO) {
        ensureLoaded()
        mutex.withLock {
            val cur = _rows.value
            val existing = cur.firstOrNull { it.kind == Kind.FAVORITE && it.key == drama.key }
            val next = if (existing != null) {
                cur - existing
            } else {
                cur + Row(
                    kind = Kind.FAVORITE,
                    key = drama.key,
                    title = drama.title,
                    cover = drama.cover,
                    knownEpisodes = drama.episodeCount,
                    updatedAt = System.currentTimeMillis(),
                )
            }
            commit(next)
        }
    }

    suspend fun removeFavorite(key: String) = withContext(Dispatchers.IO) {
        mutex.withLock { commit(_rows.value.filterNot { it.kind == Kind.FAVORITE && it.key == key }) }
    }

    /**
     * 订阅 / 取消订阅。
     *
     * 订阅时把**当前已知集数**记为基线：[Row.knownEpisodes]。以后检查到更大的集数
     * 就算"有新集"，这是连载更新提示的判定依据。
     */
    suspend fun toggleSubscribe(drama: Drama) = withContext(Dispatchers.IO) {
        ensureLoaded()
        mutex.withLock {
            val cur = _rows.value
            val existing = cur.firstOrNull { it.kind == Kind.SUBSCRIBE && it.key == drama.key }
            val next = if (existing != null) {
                cur - existing
            } else {
                cur + Row(
                    kind = Kind.SUBSCRIBE,
                    key = drama.key,
                    title = drama.title,
                    cover = drama.cover,
                    knownEpisodes = drama.episodeCount,
                    checkedEpisodes = drama.episodeCount,
                    updatedAt = System.currentTimeMillis(),
                )
            }
            commit(next)
        }
    }

    suspend fun removeSubscribe(key: String) = withContext(Dispatchers.IO) {
        mutex.withLock { commit(_rows.value.filterNot { it.kind == Kind.SUBSCRIBE && it.key == key }) }
    }

    /**
     * 订阅检查结果回写。
     *
     * @param knownEpisodes 订阅时（或上次）的集数基线
     * @param checkedEpisodes 这次检查查到的集数
     */
    suspend fun markChecked(key: String, videoTitle: String, knownEpisodes: Int, checkedEpisodes: Int) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val next = _rows.value.map { r ->
                    if (r.kind == Kind.SUBSCRIBE && r.key == key) {
                        r.copy(
                            title = videoTitle.ifBlank { r.title },
                            // 基线跟着涨，否则"已更新"提示会一直挂着
                            knownEpisodes = maxOf(r.knownEpisodes, checkedEpisodes),
                            checkedEpisodes = checkedEpisodes,
                        )
                    } else {
                        r
                    }
                }
                commit(next)
            }
        }

    /** 用户点开看了 → 清掉这个"已更新"提示。 */
    suspend fun clearUpdate(key: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            commit(
                _rows.value.map { r ->
                    if (r.kind == Kind.SUBSCRIBE && r.key == key && r.hasUpdate) {
                        r.copy(checkedEpisodes = r.knownEpisodes)
                    } else {
                        r
                    }
                },
            )
        }
    }

    /**
     * 保存播放进度（**断点续播**的核心）。
     *
     * 每次调用都写盘会太频繁（播放器每几秒就报一次位置），所以调用方
     * 负责节流（见 PlayerViewModel：暂停/切集/退出时才写，外加 10 秒一次）。
     */
    suspend fun saveProgress(
        drama: Drama,
        episodeIndex: Int,
        episodeName: String,
        positionMs: Long,
        durationMs: Long,
    ) = withContext(Dispatchers.IO) {
        ensureLoaded()
        mutex.withLock {
            val row = Row(
                kind = Kind.PROGRESS,
                key = drama.key,
                title = drama.title,
                cover = drama.cover,
                knownEpisodes = drama.episodeCount,
                episodeIndex = episodeIndex,
                episodeName = episodeName,
                positionMs = positionMs.coerceAtLeast(0L),
                durationMs = durationMs.coerceAtLeast(0L),
                updatedAt = System.currentTimeMillis(),
            )
            commit(_rows.value.filterNot { it.kind == Kind.PROGRESS && it.key == drama.key } + row)
        }
    }

    suspend fun removeProgress(key: String) = withContext(Dispatchers.IO) {
        mutex.withLock { commit(_rows.value.filterNot { it.kind == Kind.PROGRESS && it.key == key }) }
    }

    suspend fun clearHistory() = withContext(Dispatchers.IO) {
        mutex.withLock { commit(_rows.value.filterNot { it.kind == Kind.PROGRESS }) }
    }

    // ==================== 内部 ====================

    /** 落盘 + 推送。调用方需持有 [mutex]。 */
    private fun commit(next: List<Row>) {
        _rows.value = next
        TsvFile.write(FILE, next.map(::encode))
    }

    private fun encode(r: Row): String = TsvFile.encode(
        listOf(
            r.kind.name, r.key, r.title, r.cover,
            r.knownEpisodes.toString(), r.episodeIndex.toString(), r.episodeName,
            r.positionMs.toString(), r.durationMs.toString(), r.updatedAt.toString(),
            r.checkedEpisodes.toString(),
        ),
    )

    private fun parse(line: String): Row? {
        val f = TsvFile.decode(line)
        if (f.size < 4) return null
        val kind = runCatching { Kind.valueOf(f[0]) }.getOrNull() ?: return null
        if (f[1].isBlank()) return null
        return Row(
            kind = kind,
            key = f[1],
            title = f[2],
            cover = f[3],
            knownEpisodes = f.getOrNull(4)?.toIntOrNull() ?: 0,
            episodeIndex = f.getOrNull(5)?.toIntOrNull() ?: 0,
            episodeName = f.getOrNull(6).orEmpty(),
            positionMs = f.getOrNull(7)?.toLongOrNull() ?: 0L,
            durationMs = f.getOrNull(8)?.toLongOrNull() ?: 0L,
            updatedAt = f.getOrNull(9)?.toLongOrNull() ?: 0L,
            checkedEpisodes = f.getOrNull(10)?.toIntOrNull() ?: 0,
        )
    }

    /** 进度小于这个值就不值得记（刚点开就退出，记了反而碍事）。 */
    const val MIN_POSITION_MS = 10_000L

    /** 缓存占用的字节数。 */
    fun diskBytes(): Long = TsvFile.size(FILE)

    fun clearAll() {
        TsvFile.delete(FILE)
        _rows.value = emptyList()
        loaded = true
    }
}
