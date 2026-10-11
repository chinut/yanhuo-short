package com.chinut.yanhuo.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.chinut.yanhuo.core.Diag
import com.chinut.yanhuo.data.Drama
import com.chinut.yanhuo.data.DramaLibrary
import com.chinut.yanhuo.data.HomeFeed
import com.chinut.yanhuo.data.SourceRegistry
import com.chinut.yanhuo.data.UserStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 首页状态。
 *
 * # 加载策略：**先用本地库铺满，再后台补货**
 *
 * 这是从电视端项目带过来的经验，也是"有啥看啥"的前提：
 *
 * ```
 * 进首页 → 立刻读本地片库（不联网）→ 第一帧就有内容
 *        → 后台拉热榜 + 推荐流 → 到了就追加
 * ```
 *
 * 反面做法（每次都等联网）会带来两个问题：网慢时用户盯着转圈，
 * 断网时首页**完全空白** —— 用户会以为 App 坏了。
 *
 * # 为什么状态放在普通类里而不是 ViewModel
 *
 * 界面生命周期由 `remember` 控制，和 `rememberSaveable` 的 ViewModel 相比
 * 行为一致（页面在树里状态就在）。少一层依赖，也少一处
 * "ViewModel 工厂写错导致崩溃"的可能。
 */
class HomeState(private val scope: CoroutineScope) {

    /** 热剧榜（带名次）。 */
    var rank by mutableStateOf<List<Drama>>(emptyList())
        private set

    /** 推荐流（瀑布流）。 */
    var feed by mutableStateOf<List<Drama>>(emptyList())
        private set

    /** 首屏是否还在等第一批数据。 */
    var loading by mutableStateOf(true)
        private set

    /** 正在加载下一页（底部显示"加载中"）。 */
    var loadingMore by mutableStateOf(false)
        private set

    /** 推荐流是否翻到底了。 */
    var feedEnded by mutableStateOf(false)
        private set

    /** 出错时的提示（空串表示正常）。 */
    var error by mutableStateOf("")
        private set

    /** 已经请求到第几页。0 表示还没开始。 */
    private var page = 0

    /** 当前正在跑的加载任务；重新加载前先取消，避免两批数据交叉写入。 */
    private var job: Job? = null

    /** 已加载的 key 集合，用于去重（分栏轮转会有重复内容）。 */
    private val seen = HashSet<String>()

    /**
     * 本次启动的推荐流起始偏移。
     *
     * # 为什么需要它
     *
     * 推荐流是"第 N 页 = 第 N 个热词的第 1 页"这种轮转（接口没有浏览全部，
     * 只能按名字搜）。如果每次都从第 1 个热词开始，用户**每次打开看到的首页
     * 完全一样** —— 反馈就是"内容老是不变，像没有更新"。
     *
     * 所以每次进程启动随机选一个起点。
     *
     * # 为什么用它而不是打乱列表
     *
     * 打乱列表只能让"同一批内容换个顺序"，而随机起点能换出**完全不同的内容**
     * （不同热词命中的是不同题材的剧）。而且这是个 `val`，
     * 一次启动内保持不变 —— 否则下拉刷新和翻页会互相错位、同一部剧反复出现。
     *
     * ⚠️ 范围要限制在一个"热词周期"内（这里取 48，等于热词表长度）。
     * 如果随便取到几百，推荐流会直接算到第 20 多页，而单关键词实测只翻得到
     * 12 页 —— 超出就返回空，首页会变成一片空白。
     */
    private val feedOffset = (0 until 48).random()

    /**
     * 首次进入：先铺本地库。
     *
     * 注意这一步**必须是同步快路径**（只读文件、不联网）——
     * 它决定用户看到的是"秒开"还是"转圈"。
     */
    fun warmUp() {
        if (feed.isNotEmpty() || rank.isNotEmpty()) return
        val local = DramaLibrary.load()
        if (local.isNotEmpty()) {
            val ranked = local.filter { it.rank > 0 }.sortedBy { it.rank }
            // 本地铺底也打乱一下，否则断网时每次看到的顺序也一样
            val normal = local.filter { it.rank == 0 }.shuffled()
            rank = ranked
            seen += normal.map { it.key }
            feed = normal
            Diag.info("Home", "本地库铺底：榜单 ${ranked.size} 部，瀑布流 ${normal.size} 部")
        }
    }

    /**
     * 拉数据。
     *
     * @param reset true 表示下拉刷新（清掉已有内容重新拉）
     */
    fun load(reset: Boolean) {
        if (reset) {
            job?.cancel()
            page = 0
            feedEnded = false
            error = ""
            seen.clear()
            rank = emptyList()
            feed = emptyList()
            loading = true
        } else {
            if (loadingMore || feedEnded) return
            loadingMore = true
        }

        job = scope.launch {
            val src = SourceRegistry.primary()
            try {
                // ---------- 顺序很重要：先瀑布流，后榜单 ----------
                //
                // 本源的"榜单"是**自己算的**（拿 8 个热词各搜一页按热度排序），
                // 要 8 次请求；而瀑布流一页只要 1 次。先拉瀑布流，用户几乎立刻
                // 看到内容；榜单晚一两秒补上来，观感比"转圈等榜单"好得多。
                val want = if (reset) 2 else 1
                repeat(want) {
                    if (feedEnded) return@repeat
                    page += 1
                    // 加上本次启动的随机偏移 —— 这是"首页每次不一样"的关键。
                    // 同一个热词的不同页也是不同内容，所以偏移直接改变整个首屏。
                    val list = runCatching { src.page(HomeFeed.RECOMMEND, page + feedOffset) }
                        .getOrNull().orEmpty()
                    if (list.isEmpty()) {
                        feedEnded = true
                        return@repeat
                    }
                    // 分栏轮转时会有重复内容（同一部剧能被多个热词搜到），先过一遍 seen
                    val fresh = list.filter { seen.add(it.key) }
                    feed = feed + fresh
                }

                // ---------- 热榜 ----------
                if (reset || rank.isEmpty()) {
                    val r = runCatching { src.page(HomeFeed.RANK, 1) }.getOrNull().orEmpty()
                    if (r.isNotEmpty()) {
                        rank = r
                        // 榜单条目**不进瀑布流** —— 它是"精选"，瀑布流是"逛"，
                        // 重复出现会让用户觉得内容很少
                        seen += r.map { it.key }
                        Diag.info("Home", "热榜 ${r.size} 部")
                    }
                }

                if (rank.isEmpty() && feed.isEmpty()) {
                    error = "没取到内容，检查网络后下拉重试"
                    Diag.warn("Home", "首页两路都是空的")
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                error = "加载失败：${t.javaClass.simpleName}"
                Diag.error("Home", "首页加载异常", t)
            } finally {
                loading = false
                loadingMore = false
            }
        }
    }

    /**
     * 用最新的本地库数据刷新已显示条目的集数（详情页补全后回到首页时用）。
     *
     * 为什么需要：列表里的 `total_num` 是抓取时的快照。用户进详情页看到的是
     * 详情接口的实时值，可能更大（连载中）。回到首页时把这份更新的值覆盖上去，
     * 免得同一部剧在两个界面上集数不一致。
     *
     * 注：以前这里还有一个 `refreshRankEpisodeCounts`（逐个开详情页补榜单条目的集数），
     * 那是为红果准备的 —— 红果的榜单页只给名次和热度、不给集数。换成七猫后，
     * 搜索列表**直接带 `total_num`**，那个函数永远走不到分支，已删除。
     */
    fun syncEpisodeCounts() {
        val lib = DramaLibrary.load().associateBy { it.key }
        fun fix(list: List<Drama>) = list.map { d ->
            val n = lib[d.key]?.episodeCount ?: 0
            if (n > d.episodeCount) d.copy(episodeCount = n) else d
        }
        if (rank.isNotEmpty()) rank = fix(rank)
        if (feed.isNotEmpty()) feed = fix(feed)
    }

    /** 继续观看列表（从本地进度表来）。 */
    fun continueRows() = UserStore.continueWatching(limit = 12)
}

/** 首页数据是否已经就绪（决定要不要显示骨架屏）。 */
val HomeState.ready: Boolean get() = !loading || feed.isNotEmpty() || rank.isNotEmpty()
