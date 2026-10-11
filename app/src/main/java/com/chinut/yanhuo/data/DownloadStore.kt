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
 * 离线下载的记录表。
 *
 * 存"哪部剧的哪一集已经下好了"，一张 TSV 表，和 [UserStore] 一个套路。
 *
 * # 为什么还要单独存一份记录，不直接扫目录
 *
 * 目录结构（`offline/<源>_<剧>/<集>/index.m3u8`）本身已经能表达"下过什么"，
 * 理论上扫一遍就知道。但仍然要这张表，因为：
 *   · 文件名里**没有剧名/封面/集名** —— 光看目录只能得到 id，
 *     离线页面上要显示"哪部剧、第几集、多大、什么时候下的"，这些必须存；
 *   · 扫目录在集数多时（一部剧上百集）会成为列表滚动的性能问题；
 *   · 需要"下载时间"来做排序。
 *
 * 记录与磁盘上真实存在的文件**以磁盘为准**：读取时逐条校验 m3u8 是否还在，
 * 不在就丢弃（用户可能在系统设置里清了数据，或我们清理过）。
 */
object DownloadStore {

    private const val FILE = "downloads.tsv"
    private const val TAG = "DownloadStore"

    /** 一条离线记录。 */
    data class Row(
        val key: String,          // "sourceId:targetId"
        val episodeIndex: Int,
        val title: String = "",
        val cover: String = "",
        val episodeName: String = "",
        val bytes: Long = 0L,
        val createdAt: Long = 0L,
    ) {
        /** 同一个剧集组合的稳定 id（列表 key 用）。 */
        val id: String get() = "$key#$episodeIndex"
    }

    private val mutex = Mutex()
    private var loaded = false

    private val _rows = MutableStateFlow<List<Row>>(emptyList())
    val rows: StateFlow<List<Row>> = _rows.asStateFlow()

    // ==================== 读 ====================

    suspend fun ensureLoaded() = withContext(Dispatchers.IO) {
        if (loaded) return@withContext
        mutex.withLock {
            if (loaded) return@withLock
            val disk = TsvFile.read(FILE).mapNotNull(::parse)
            // 以磁盘为准：记录还在但文件被删了（清数据/手动删）就丢弃，
            // 否则界面会显示一个点开不能播的条目
            val alive = disk.filter { r ->
                val d = dramaOf(r)
                OfflineDownloader.isDownloaded(d, r.episodeIndex)
            }
            if (alive.size != disk.size) {
                Diag.info(TAG, "清理失效记录 ${disk.size - alive.size} 条")
                persistLocked(alive)
            }
            _rows.value = alive
            loaded = true
            Diag.info(TAG, "离线记录读取完成：${alive.size} 集")
        }
    }

    /** 全部离线记录，按下载时间**倒序**（最近下的排前面）。 */
    fun all(): List<Row> = _rows.value.sortedByDescending { it.createdAt }

    /** 按剧分组：同一部剧的多集聚在一起，界面按剧展示。 */
    fun byDrama(): List<Pair<String, List<Row>>> =
        all().groupBy { it.key }.map { (k, v) -> k to v.sortedBy { it.episodeIndex } }

    fun hasDrama(key: String): Boolean = _rows.value.any { it.key == key }

    /** 某部剧已下载的集号集合（详情页标"已下载"用）。 */
    fun downloadedEpisodes(key: String): Set<Int> =
        _rows.value.filter { it.key == key }.map { it.episodeIndex }.toSet()

    /** 总占用字节。 */
    fun totalBytes(): Long = _rows.value.sumOf { it.bytes }

    // ==================== 写 ====================

    /** 记一集下载完成。[path] 是本地 m3u8 路径，只用来核对存在性。 */
    suspend fun add(
        drama: Drama,
        episode: Episode,
        bytes: Long,
        onChanged: () -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        ensureLoaded()
        mutex.withLock {
            val row = Row(
                key = drama.key,
                episodeIndex = episode.index,
                title = drama.title,
                cover = drama.cover,
                episodeName = episode.name.ifBlank { "第 ${episode.index} 集" },
                bytes = bytes,
                createdAt = System.currentTimeMillis(),
            )
            // 同一集重复下载（重下）时覆盖旧记录，不产生重复行
            val next = _rows.value.filterNot {
                it.key == row.key && it.episodeIndex == row.episodeIndex
            } + row
            _rows.value = next
            persistLocked(next)
        }
        onChanged()
    }

    /** 删一集（连同文件）。 */
    suspend fun remove(dramaKey: String, episodeIndex: Int) = withContext(Dispatchers.IO) {
        val row = _rows.value.firstOrNull {
            it.key == dramaKey && it.episodeIndex == episodeIndex
        }
        if (row != null) {
            OfflineDownloader.deleteEpisode(dramaOf(row), episodeIndex)
        }
        mutex.withLock {
            val next = _rows.value.filterNot {
                it.key == dramaKey && it.episodeIndex == episodeIndex
            }
            _rows.value = next
            persistLocked(next)
        }
    }

    /** 删一部剧的全部离线内容。 */
    suspend fun removeDrama(dramaKey: String) = withContext(Dispatchers.IO) {
        _rows.value.firstOrNull { it.key == dramaKey }?.let {
            OfflineDownloader.deleteDrama(dramaOf(it))
        }
        mutex.withLock {
            val next = _rows.value.filterNot { it.key == dramaKey }
            _rows.value = next
            persistLocked(next)
        }
    }

    /** 清空全部离线内容。 */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        OfflineDownloader.clearAll()
        mutex.withLock {
            _rows.value = emptyList()
            persistLocked(emptyList())
        }
        Diag.info(TAG, "已清空全部离线内容")
    }

    // ==================== 内部 ====================

    /**
     * 从记录还原出一个 [Drama]。
     *
     * 只用于按 key 找目录、算大小 —— 所以只要 sourceId/targetId 对就行，
     * 其余字段原样带回去让界面少查一次。
     */
    private fun dramaOf(r: Row) = Drama(
        sourceId = r.key.substringBefore(':'),
        targetId = r.key.substringAfter(':'),
        title = r.title,
        cover = r.cover,
        episodeCount = 0,
    )

    private fun persistLocked(list: List<Row>) {
        TsvFile.write(FILE, list.map(::encode))
    }

    private fun encode(r: Row): String = TsvFile.encode(
        listOf(
            r.key,
            r.episodeIndex.toString(),
            r.title,
            r.cover,
            r.episodeName,
            r.bytes.toString(),
            r.createdAt.toString(),
        ),
    )

    private fun parse(line: String): Row? {
        val f = TsvFile.decode(line)
        if (f.size < 2) return null
        val key = f[0]
        if (!key.contains(':')) return null
        val idx = f.getOrNull(1)?.toIntOrNull() ?: return null
        return Row(
            key = key,
            episodeIndex = idx,
            title = f.getOrNull(2).orEmpty(),
            cover = f.getOrNull(3).orEmpty(),
            episodeName = f.getOrNull(4).orEmpty(),
            bytes = f.getOrNull(5)?.toLongOrNull() ?: 0L,
            createdAt = f.getOrNull(6)?.toLongOrNull() ?: 0L,
        )
    }
}
