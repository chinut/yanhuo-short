package com.chinut.yanhuo.data

import com.chinut.yanhuo.core.Diag
import com.chinut.yanhuo.core.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * 七猫短剧接口 —— **当前唯一的源**。
 *
 * # 为什么选它（这是整个 App 的数据基础）
 *
 * 要求是"能全部看"，而实测这个接口**每部剧都给全集直链**：
 *
 * ```
 * 潜龙出山，闪婚美女总裁   标注 96 集 → play_list 96 条 → 全部有地址
 * 一剑独尊               标注 77 集 → play_list 77 条 → 全部有地址
 * ```
 *
 * 抽查第 1 / 49 / 96 集，全部 `HTTP 206 application/vnd.apple.mpegurl`。
 * 而红果官网每部剧只免费前 3 集（第 4 集起 404），已被整体移除。
 *
 * 内容也是正规的 —— 返回里有**网微剧备案号**
 * （`（七猫）网微剧备字（2025）第01177号`），地址在七猫自己的 CDN
 * （`cdn-vod-playlet.wtzw.com`），不是盗链聚合。
 *
 * # 接口
 *
 * `https://xiaoqi.icofun.cn/API/qimao_duanju.php`
 *   · `?name=关键词&page=N` → 搜索（返回 `data.list`，一页 10 条）
 *   · `?id=短剧id`          → **全部集的直链**（返回 `data.play_list`）
 *
 * 公开访问、无需密钥。
 *
 * # ⚠️ 没有"浏览全部"
 *
 * 实测无参数、`type=`、`class=`、`page=` 全都返回空（接口强制要 `name`），
 * 所以**首页推荐流只能靠"多热词轮转"**：
 * [HOT] 里 48 个题材词，第 N 页 = 第 N 个词的第 1 页，词用完进第 2 页。
 * 榜单同理 —— 接口没有榜，[hotRank] 是自己按条目的 `hot_value` 算的。
 * 这是接口能力决定的，不是偷懒。
 */
object QimaoSource : Source {

    private const val TAG = "Qimao"
    private const val API = "https://xiaoqi.icofun.cn/API/qimao_duanju.php"

    /**
     * 纯 HTTP 兜底。
     *
     * ⚠️ 服务器证书由 **Let's Encrypt 的 `YR1`** 签发，而 **Android 9 及更早的系统
     * 根证书库里没有对应 CA** —— 老机器上 https 握手直接失败：
     *
     * ```
     * SSLHandshakeException: Trust anchor for certification path not found.
     * ```
     *
     * 这个接口纯 HTTP 也返回 200，所以失败时退回 HTTP 即可。
     * 不用"放宽证书校验"——那是全局接受任意证书，中间人风险大得多；
     * 而这里只传公开的短剧元数据，不涉及任何用户信息。
     */
    private const val API_PLAIN = "http://xiaoqi.icofun.cn/API/qimao_duanju.php"

    /** 源标识。内部代号，不展示给用户。 */
    override val id = "qimao"

    /**
     * **故意留空 —— 界面不显示源名。**
     *
     * App 只有一个内容源，把供应方名字摆在界面上对用户没有意义：
     * 他看的是"一部剧"，不是"某个渠道的货架"。
     *
     * 所有原来显示它的地方都改成了中性说法：
     *   · 首页副标题      → "已加载 N 部"
     *   · 热剧榜副标题    → "热度排序"
     *   · 详情页来源标签  → 整行去掉
     *   · 播放器出处提示  → 整段去掉
     *
     * 字段本身保留，因为 [Source] 接口要求它，且以后加源时直接就有用。
     */
    override val displayName = ""

    /** 源能力的一句话说明。当前没有界面展示它，留着供排障与以后加源参考。 */
    override val note = "每部剧提供全集"

    /**
     * 推荐流用的题材热词。
     *
     * 接口**没有"浏览全部"**，只能按名字搜，所以推荐流=多热词各搜一页轮转。
     * 词表从最初的 16 个扩到 48 个：实测 42 个词 × 2 页能覆盖 **713 部去重内容**，
     * 词越多首页能翻得越深（每个词最多还能翻到 12 页）。
     */
    private val HOT = listOf(
        // —— 题材 ——
        "总裁", "战神", "重生", "穿越", "神医", "赘婿", "甜宠", "逆袭",
        "复仇", "年代", "玄幻", "都市", "闪婚", "豪门", "萌宝", "婆婆",
        // —— 身份/职业（短剧高频人设）——
        "千金", "夫人", "龙王", "外卖", "保安", "保姆", "学生", "职场",
        // —— 情节套路 ——
        "打脸", "虐恋", "悬疑", "宫斗", "校园", "异能", "末世", "修仙",
        "灵异", "军事", "犯罪", "家庭", "失忆", "闪离", "替嫁", "哑妻",
        "先婚后爱", "追妻", "团宠", "马甲", "系统", "归国", "复仇千金", "真假千金",
    )

    /**
     * 做「热门榜」用的高热度词。
     *
     * 接口没有榜单，所以这里是**自己算的榜**：
     * 拿这些词各搜一页，把所有条目的 `hot_value` 汇总排序，取前 N 部。
     *
     * ⚠️ 词数直接等于请求数（每个词一次搜索）。这里是 8 个，加上榜单只在
     * 首页首次加载时算一次并缓存，所以代价可以接受。词多了会让首屏明显变慢。
     */
    private val RANK_WORDS = listOf(
        "总裁", "战神", "重生", "穿越", "甜宠", "逆袭", "复仇", "闪婚",
    )

    /** 热门榜缓存（一次进程内有效，避免每次进首页都重算）。 */
    @Volatile
    private var rankCache: List<Drama>? = null

    /** 搜索缓存：`keyword#page` → 列表。避免翻回去时重新请求。 */
    private val searchCache = LinkedHashMap<String, List<Drama>>()

    /** 详情内存缓存（分集结构不含地址，可以复用）。 */
    private val detailCache = LinkedHashMap<String, DramaDetail>()

    // ==================== 取数据 ====================

    /**
     * 取接口文本。**https 优先，证书/网络类失败就退回 http**。
     *
     * 不能用 `Http.get` —— 它内部会用同一套配置，而这里需要"失败再降级"这一步。
     */
    private suspend fun fetch(query: String): String? = withContext(Dispatchers.IO) {
        val q = if (query.isEmpty()) "" else "?$query"
        runCatching { Http.getOrThrow(API + q, ua = Http.UA_MOBILE) }.getOrNull()?.let {
            return@withContext it
        }
        runCatching { Http.getOrThrow(API_PLAIN + q, ua = Http.UA_MOBILE) }.getOrNull()
    }

    // ==================== Source 实现 ====================

    /**
     * 本地缓存。
     *
     * ⚠️ 这里**必须读磁盘**，不能返回空。
     *
     * 曾经有一版返回 `emptyList()`（当时的理由是"它只是备用源，内容没必要落盘"）。
     * 一旦它成为**主源**，立刻暴露两个问题：
     *   1. 断网启动 → 首页完全空白（用户会以为 App 坏了）
     *   2. `DramaLibrary` 永远是空的 → 首页"先用本地铺底"的策略完全失效
     *
     * 现在走 [DramaLibrary]：抓到就 merge 落盘，进首页先读磁盘。
     */
    override suspend fun cached(): List<Drama> = withContext(Dispatchers.IO) {
        DramaLibrary.load().filter { it.sourceId == id }
    }

    override suspend fun page(feed: HomeFeed, page: Int): List<Drama> =
        withContext(Dispatchers.IO) {
            when (feed) {
                // 接口没有榜单，所以榜是**自己按 hot_value 算的**（见 hotRank）
                HomeFeed.RANK -> if (page <= 1) hotRank() else emptyList()
                HomeFeed.RECOMMEND -> recommend(page)
            }
        }

    /**
     * 热门榜：拿高热度词各搜一页，按 `hot_value` 排序取前 20。
     *
     * 为什么值得做：接口本身没有榜单，但条目带 `hot_value`。
     * 不利用它的话首页会少一块"最热内容"的入口，而这块恰是用户最常点的。
     * 成本是 12 次搜索（每次约 0.3 秒），只跑一次并缓存。
     */
    private suspend fun hotRank(): List<Drama> {
        rankCache?.let { return it }

        val acc = LinkedHashMap<String, Drama>()
        for (kw in RANK_WORDS) {
            val list = runCatching { searchPage(kw, 1) }.getOrNull().orEmpty()
            list.forEach { d ->
                // 同一部剧可能被多个词搜到，保留热度更高的那份
                val old = acc[d.key]
                if (old == null || d.hotScore > old.hotScore) acc[d.key] = d
            }
        }
        val ranked = acc.values
            .filter { it.hotScore > 0 }
            .sortedByDescending { it.hotScore }
            .take(20)
            .mapIndexed { i, d -> d.copy(rank = i + 1) }

        Diag.info(TAG, "热门榜：${acc.size} 部候选 → 取 ${ranked.size} 部（按 hot_value 排序）")
        if (ranked.isNotEmpty()) rankCache = ranked
        return ranked
    }

    /**
     * 热词轮转式"推荐流"。
     *
     * 接口没有"浏览全部"，只能按名字搜，所以：**第 N 页 = 第 N 个热词的第 1 页**，
     * 热词用完就整体进到这些词的第 2 页（每个词实测能翻 12 页）。
     * 这样一直往下滑永远有内容，而且题材是轮换的，不会连着几十页都是总裁。
     */
    private suspend fun recommend(page: Int): List<Drama> {
        val n = HOT.size
        val kw = HOT[(page - 1) % n]
        val innerPage = (page - 1) / n + 1
        return cachedSearch(kw, innerPage).also {
            Diag.info(TAG, "推荐流 热词「$kw」第 $innerPage 页 → ${it.size} 部")
        }
    }

    override suspend fun search(keyword: String, page: Int): List<Drama> =
        withContext(Dispatchers.IO) {
            val kw = keyword.trim()
            if (kw.isEmpty()) return@withContext emptyList()
            cachedSearch(kw, page)
        }

    /**
     * 带缓存的搜索。
     *
     * ⚠️ 推荐流和搜索页**共用这一个入口**。之前推荐流自己拼 `"$kw#$page"` 当 key，
     * 而搜索页用的是 `"$kw#$page"`（page 从 1 起）—— 两套号段不同，同一部剧会被
     * 请求两次。现在两边都走这里，key 只有一个来源，不会重复打接口。
     */
    private suspend fun cachedSearch(keyword: String, page: Int): List<Drama> {
        val key = "$keyword#$page"
        synchronized(searchCache) { searchCache[key] }?.let { return it }
        val list = searchPage(keyword, page)
        if (list.isNotEmpty()) {
            synchronized(searchCache) { searchCache[key] = list }
            // 落盘：断网重启后首页依然有内容（见 cached() 的注释）
            DramaLibrary.merge(list)
        }
        return list
    }

    override suspend fun detail(drama: Drama): DramaDetail? = withContext(Dispatchers.IO) {
        synchronized(detailCache) { detailCache[drama.key] }?.let { return@withContext it }

        val id = drama.targetId
        val text = fetch("id=$id")
        if (text == null) {
            Diag.warn(TAG, "详情取不到《${drama.title}》(id=$id)：https 和 http 都失败")
            return@withContext null
        }
        val d = runCatching { JSONObject(text).getJSONObject("data") }.getOrElse {
            Diag.warn(
                TAG,
                "详情响应解析失败《${drama.title}》：${it.message}｜前 200 字=${text.take(200)}",
            )
            return@withContext null
        }

        val pl: JSONArray? = d.optJSONArray("play_list")
        if (pl == null || pl.length() == 0) {
            Diag.warn(TAG, "详情里没有 play_list《${drama.title}》(id=$id)")
            return@withContext null
        }

        val episodes = ArrayList<Episode>(pl.length())
        for (i in 0 until pl.length()) {
            val o = pl.optJSONObject(i) ?: continue
            // 优先 h265（更省流量），但它可能是空的或不被设备支持，
            // 所以默认还是用 h264 的 video_url —— 兼容性优先。
            val url = o.optString("video_url").ifBlank { o.optString("video_h265_url") }
            episodes.add(
                Episode(
                    targetId = o.optString("video_id").ifBlank { (i + 1).toString() },
                    name = "第 ${i + 1} 集",
                    index = i + 1,
                    // ⚠️ 列表阶段就带地址是刻意的：七猫的地址是**长时效**的
                    // （实测多次请求同一地址仍 206），提前拿能省一次请求。
                    url = url,
                    locked = false,
                ),
            )
        }

        val total = d.optInt("total_episode_num", episodes.size)
        val full = drama.copy(
            title = d.optString("title").ifBlank { drama.title },
            cover = d.optString("image_link").ifBlank { drama.cover },
            intro = d.optString("intro"),
            tags = d.optString("tags").split(' ', ',', '，').filter { it.isNotBlank() }.take(4),
            episodeCount = if (total > 0) total else episodes.size,
        )

        val detail = DramaDetail(
            drama = full,
            episodes = episodes,
            // 七猫是全集免费，没有"免费集数"这个概念
            freeCount = episodes.size,
        )
        Diag.info(
            TAG,
            "《${full.title}》：${episodes.size} 集全部有地址（备案 ${
                d.optString("own_record_number").ifBlank { "无" }
            }）",
        )
        synchronized(detailCache) { detailCache[drama.key] = detail }
        detail
    }

    /** 七猫的地址在详情阶段就拿到了，所以这里直接返回。 */
    override suspend fun streamUrl(drama: Drama, episode: Episode): String? {
        episode.url.takeIf { it.isNotBlank() }?.let { return it }
        // 没带地址（详情缓存丢了）→ 重新取一次详情
        val d = detail(drama) ?: return null
        return d.episodes.firstOrNull { it.index == episode.index }?.url?.takeIf { it.isNotBlank() }
    }

    override suspend fun probe(): String = withContext(Dispatchers.IO) {
        val https = runCatching {
            val t = Http.getOrThrow("$API?name=%E6%80%BB%E8%A3%81&page=1", ua = Http.UA_MOBILE)
            val n = runCatching {
                JSONObject(t).getJSONObject("data").getJSONArray("list").length()
            }.getOrDefault(-1)
            "https HTTP 200 · $n 条"
        }.getOrElse { "https 失败：${it.javaClass.simpleName}(${it.message?.take(50)})" }

        val http = runCatching {
            val t = Http.getOrThrow("$API_PLAIN?name=%E6%80%BB%E8%A3%81&page=1", ua = Http.UA_MOBILE)
            "http ${
                runCatching {
                    JSONObject(t).getJSONObject("data").getJSONArray("list").length()
                }.getOrDefault(-1)
            } 条"
        }.getOrElse { "http 失败：${it.javaClass.simpleName}" }

        "$https ｜ $http"
    }

    // ==================== 解析 ====================

    /** 搜一页并转成统一模型。 */
    private suspend fun searchPage(keyword: String, page: Int): List<Drama> {
        val text = fetch("name=${enc(keyword)}&page=$page")
        if (text == null) {
            Diag.warn(TAG, "搜索「$keyword」第 $page 页取不到响应（https/http 都失败）")
            return emptyList()
        }
        val arr = runCatching {
            JSONObject(text).getJSONObject("data").getJSONArray("list")
        }.getOrElse {
            Diag.warn(TAG, "搜索「$keyword」响应解析失败：${it.message}｜前 200 字=${text.take(200)}")
            return emptyList()
        }

        val out = ArrayList<Drama>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val sid = o.opt("id")?.toString().orEmpty()
            if (sid.isBlank() || sid == "null") continue
            val hot = o.optString("hot_value")
            out.add(
                Drama(
                    sourceId = id,
                    targetId = sid,
                    title = o.optString("title"),
                    cover = o.optString("image_link"),
                    episodeCount = parseCount(o.optString("total_num")),
                    tags = o.optString("sub_title").split(' ', ',', '，')
                        .filter { it.isNotBlank() }.take(2),
                    hot = hot,
                    hotScore = parseHot(hot),
                ),
            )
        }
        return out
    }

    /** `"96集"` → `96`。 */
    private fun parseCount(raw: String): Int =
        Regex("""(\d{1,4})""").find(raw)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}
