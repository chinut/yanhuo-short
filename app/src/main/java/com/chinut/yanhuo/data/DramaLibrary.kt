package com.chinut.yanhuo.data

import com.chinut.yanhuo.YanhuoApp
import com.chinut.yanhuo.core.Diag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 本地小表存储。
 *
 * 为什么不用数据库：这里存的东西**结构简单、字段少、总量可控**
 * （片库几千行、收藏几十行、进度几十行）。用一行一条的文本就够，
 * 省掉 Room 那一整套（注解处理器、schema 迁移、额外的线程池）。
 *
 * 格式：每行一条记录，字段用 U+001F（ASCII "Unit Separator"）分隔。
 * 选这个字符是因为剧名/简介里几乎不可能出现它，比逗号、竖线安全得多。
 *
 * 写入一律**先写临时文件再改名**：手机随时可能被杀进程/断电，
 * 半截文件解析出来是"半份列表"，比空还糟（用户会以为内容丢了）。
 */
internal object TsvFile {

    private const val SEP = '\u001F'

    private fun file(name: String) = File(YanhuoApp.ctx().filesDir, name)

    /** 转义：把分隔符和换行替换掉，保证不会把记录读串。 */
    private fun esc(s: String): String = s
        .replace(SEP.toString(), " ")
        .replace("\n", " ")
        .replace("\r", " ")

    fun encode(fields: List<String>): String = fields.joinToString(SEP.toString()) { esc(it) }

    fun decode(line: String): List<String> = line.split(SEP)

    fun read(name: String): List<String> {
        val f = file(name)
        if (!f.exists()) return emptyList()
        return runCatching { f.readLines() }.getOrElse {
            Diag.warn("TsvFile", "$name 读取失败：${it.javaClass.simpleName} ${it.message}")
            emptyList()
        }.filter { it.isNotBlank() }
    }

    fun write(name: String, lines: List<String>) {
        runCatching {
            val f = file(name)
            val tmp = File(f.parentFile, "$name.tmp")
            tmp.writeText(lines.joinToString("\n"))
            if (!tmp.renameTo(f)) {
                // 某些机型上 renameTo 会失败（目标存在且被占用），退回直接写
                f.writeText(tmp.readText())
                tmp.delete()
            }
        }.onFailure {
            Diag.error("TsvFile", "写 $name 失败", it)
        }
    }

    fun delete(name: String) {
        runCatching { file(name).delete() }
    }

    fun size(name: String): Long = runCatching { file(name).length() }.getOrDefault(0L)
}

/**
 * 片库落盘。
 *
 * # 为什么必须有它
 *
 * 首页的内容全来自联网。如果只放进程内缓存，**断网重启后首页就是空白** ——
 * 电视端那个项目踩过这个坑（用户反馈"断网之后短剧里面没内容了"）。
 * 所以列表抓到一部分就**立刻落盘**，下次进首页先用磁盘上的旧列表铺满，
 * 再后台刷新。这样"有没有网"都不影响打开就有东西看。
 *
 * # 集数会被"只增不减"地更新
 *
 * 列表页能直接拿到 `全 N 集`，热榜页拿不到。所以同一部剧：
 * 列表拿到 69 集 → 存 69；之后热榜给 0 集 → **不覆盖**，仍保留 69。
 * 详情页拿到的 `episode_cnt` 同理（它更权威，也只在更大时覆盖）。
 * 这个规则同时让"连载更新检测"变简单：集数变大 = 有新集。
 */
object DramaLibrary {

    private const val TAG = "DramaLibrary"
    private const val FILE = "drama_library.tsv"

    /** 上限。每条平均约 150 字节，4000 条 ≈ 600KB，可接受。 */
    private const val MAX_ITEMS = 4000

    private val mutex = Mutex()

    @Volatile
    private var memo: LinkedHashMap<String, Drama>? = null

    @Volatile
    private var dirty = false

    private fun ensureLoaded(): LinkedHashMap<String, Drama> {
        memo?.let { return it }
        synchronized(this) {
            memo?.let { return it }
            val map = LinkedHashMap<String, Drama>()
            TsvFile.read(FILE).forEach { line ->
                parse(line)?.let { map[it.key] = it }
            }
            Diag.info(TAG, "从磁盘读到 ${map.size} 部")
            memo = map
            return map
        }
    }

    /** 读全部（不联网）。 */
    fun load(): List<Drama> = ensureLoaded().values.toList()

    /** 查一部。 */
    fun find(key: String): Drama? = ensureLoaded()[key]

    /**
     * 合并一批（列表 / 详情抓到的都算）。
     *
     * 规则：
     *   · 新条目直接加
     *   · 已存在时 **只更新集数、封面、热度**，不覆盖已有的简介/标签（更完整的那份留着）
     *   · 集数**只增不减** —— 热榜给 0 不会把列表给的 69 冲掉
     */
    suspend fun merge(items: List<Drama>) = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext
        mutex.withLock {
            val map = ensureLoaded()
            var changed = false
            items.forEach { d ->
                if (d.targetId.isBlank() || d.title.isBlank()) return@forEach
                val old = map[d.key]
                val merged = if (old == null) {
                    d
                } else {
                    old.copy(
                        title = d.title.ifBlank { old.title },
                        cover = d.cover.ifBlank { old.cover },
                        // 只增不减：连载更新检测依赖这个语义
                        episodeCount = maxOf(old.episodeCount, d.episodeCount),
                        intro = d.intro.ifBlank { old.intro },
                        tags = if (d.tags.isNotEmpty()) d.tags else old.tags,
                        hot = d.hot.ifBlank { old.hot },
                        hotScore = maxOf(old.hotScore, d.hotScore),
                        // 名次只保留第一次拿到的非 0 值（榜单顺序不应被后来的推荐流冲掉）
                        rank = if (d.rank > 0) d.rank else old.rank,
                    )
                }
                if (merged != old) {
                    map[d.key] = merged
                    changed = true
                }
            }
            if (!changed) return@withLock

            // 超上限时丢最旧的（LinkedHashMap 保持插入序，靠前的就是最旧的）
            while (map.size > MAX_ITEMS) {
                val first = map.keys.firstOrNull() ?: break
                map.remove(first)
            }
            dirty = true
            flushLocked(map)
        }
    }

    /** 落盘。调用方需持有 [mutex]。 */
    private fun flushLocked(map: Map<String, Drama>) {
        val lines = map.values.map { encode(it) }
        TsvFile.write(FILE, lines)
        dirty = false
        Diag.info(TAG, "片库落盘 ${lines.size} 部")
    }

    /** 如果还没落盘就落一次（进后台 / 退出时调用）。 */
    suspend fun flushIfDirty() = withContext(Dispatchers.IO) {
        if (!dirty) return@withContext
        mutex.withLock {
            if (dirty) flushLocked(ensureLoaded())
        }
    }

    fun clear() {
        TsvFile.delete(FILE)
        synchronized(this) {
            memo = null
            dirty = false
        }
    }

    /** 磁盘占用（"我的"页显示缓存大小用）。 */
    fun diskBytes(): Long = TsvFile.size(FILE)

    // ==================== 序列化 ====================

    /** 字段顺序：新增字段只能追加到末尾，否则老缓存会错位。 */
    private fun encode(d: Drama): String = TsvFile.encode(
        listOf(
            d.sourceId,
            d.targetId,
            d.title,
            d.cover,
            d.episodeCount.toString(),
            d.tags.joinToString(","),
            d.hot,
            d.intro,
            d.rank.toString(),
            d.hotScore.toString(),
        ),
    )

    private fun parse(line: String): Drama? {
        val f = TsvFile.decode(line)
        if (f.size < 5) return null
        val sourceId = f[0]
        val targetId = f[1]
        if (sourceId.isBlank() || targetId.isBlank()) return null
        return Drama(
            sourceId = sourceId,
            targetId = targetId,
            title = f[2],
            cover = f[3],
            episodeCount = f[4].toIntOrNull() ?: 0,
            tags = f.getOrNull(5).orEmpty().split(",").filter { it.isNotBlank() },
            hot = f.getOrNull(6).orEmpty(),
            intro = f.getOrNull(7).orEmpty(),
            rank = f.getOrNull(8)?.toIntOrNull() ?: 0,
            hotScore = f.getOrNull(9)?.toLongOrNull() ?: 0L,
        )
    }
}
