package com.chinut.yanhuo.data

import android.net.Uri
import com.chinut.yanhuo.core.Diag
import com.chinut.yanhuo.core.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 源注册表 —— **界面与数据源之间的唯一入口**。
 *
 * # 当前只有一个源
 *
 * 主源是**七猫短剧**：实测每部剧都给全集直链（96 集的剧 96 集全有地址），
 * 满足"能全部看"这个硬要求，所以：
 *
 *   · 红果短剧（官网每部剧只免费前 3 集、第 4 集起 404）—— **已移除**
 *   · 跨源剧名匹配（`TitleMatch`）、备用源缓存、回退分支 —— **随之删除**
 *
 * 留这个类而不是让界面直接调 `QimaoSource`，是因为"界面不直接依赖具体源"
 * 这条约定值得保住：以后要加源（或把源做成外部配置），改这一个文件 + 新增一个
 * `Source` 实现即可，**界面一行都不用动**。这不是过度设计 —— 这个项目已经
 * 因为换源而改过一次主源了，留一个接缝的代价远小于到处改调用点。
 */
object SourceRegistry {

    private const val TAG = "SourceRegistry"

    /** 所有可用源。顺序即优先级（第一个是主源）。 */
    private val all: List<Source> by lazy { listOf<Source>(QimaoSource) }

    private val byId: Map<String, Source> by lazy { all.associateBy { it.id } }

    fun init() {
        Diag.info(TAG, "可用源：${all.joinToString { "${it.id}(${it.displayName})" }}")
    }

    /** 全部源，给设置页列出。 */
    fun sources(): List<Source> = all

    /**
     * 当前使用的源。
     *
     * 目前只有一个，但配置里可能残留旧值（例如升级前存过 `hongguo`），
     * 所以这里做一次兜底：查不到就退回列表第一个，**不让 App 起不来**。
     */
    fun primary(): Source =
        byId[Prefs.primarySource] ?: all.first().also {
            if (Prefs.primarySource.isNotBlank() && Prefs.primarySource != it.id) {
                Diag.warn(TAG, "配置的源 `${Prefs.primarySource}` 不存在，用 ${it.id}")
            }
        }

    fun byId(id: String): Source? = byId[id]

    // ==================== 详情 ====================

    /** 取一部剧的详情（全部分集）。 */
    suspend fun detail(drama: Drama): DramaDetail? {
        val src = byId[drama.sourceId] ?: primary()
        return src.detail(drama)
    }

    // ==================== 播放地址 ====================

    /**
     * 解析某一集的播放地址。
     *
     * **先看本地有没有下载过** —— 有就直接用本地 m3u8，这是离线播放的入口。
     * 否则再走源接口（七猫的详情接口一次返回全集直链，所以正常情况下这里
     * 直接命中 [Episode.url]，不会再有额外请求）。
     *
     * @return 取不到返回 null。调用方（播放器）要如实告诉用户，
     *   **不要**随便换一集或换一部剧顶上 —— 那比"看不了"更糟。
     */
    suspend fun resolve(drama: Drama, episode: Episode): ResolvedStream? =
        withContext(Dispatchers.IO) {
            // ---------- 1. 本地优先 ----------
            //
            // 放在最前面（而不是"联网失败再退回本地"）的原因：
            //   · 离线时联网那一步会先卡十几秒超时，用户以为卡死了；
            //   · 用户明确下载过就是想离线看、想省流量，不该再去打网络。
            if (OfflineDownloader.isDownloaded(drama, episode.index)) {
                val f = OfflineDownloader.localPlaylist(drama, episode.index)
                Diag.info(TAG, "第 ${episode.index} 集 ← 本地离线文件")
                return@withContext ResolvedStream(
                    url = Uri.fromFile(f).toString(),
                    source = byId[drama.sourceId] ?: primary(),
                )
            }

            // ---------- 2. 联网取 ----------
            val src = byId[drama.sourceId] ?: primary()
            val url = runCatching { src.streamUrl(drama, episode) }.getOrNull()
            if (url.isNullOrBlank()) {
                Diag.warn(
                    TAG,
                    "第 ${episode.index} 集取不到地址《${drama.title}》" +
                        "（源 ${src.id}，locked=${episode.locked}）",
                )
                return@withContext null
            }
            ResolvedStream(url = url, source = src)
        }

    // ==================== 连载更新检查 ====================

    /**
     * 检查一部订阅剧的集数变化。
     *
     * **只信详情接口**：列表里的 `total_num` 是抓取时的快照，可能滞后；
     * 详情里的 `total_episode_num` 才是当前真实值。
     *
     * @return `(最新集数, 最新标题)`；失败返回 null —— 这时调用方**不要**改动基线，
     *   否则一次网络抖动会把"已更新"永久抹掉。
     */
    suspend fun checkEpisodes(drama: Drama): Pair<Int, String>? = withContext(Dispatchers.IO) {
        val src = byId[drama.sourceId] ?: primary()
        val d = runCatching { src.detail(drama) }.getOrNull()
        if (d == null) {
            Diag.warn(TAG, "订阅检查失败《${drama.title}》")
            return@withContext null
        }
        val n = if (d.drama.episodeCount > 0) d.drama.episodeCount else d.episodes.size
        if (n <= 0) return@withContext null
        Pair(n, d.drama.title)
    }
}
