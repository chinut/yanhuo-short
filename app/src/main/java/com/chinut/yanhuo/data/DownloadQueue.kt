package com.chinut.yanhuo.data

import com.chinut.yanhuo.core.Diag
import com.chinut.yanhuo.core.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 下载队列。
 *
 * # 为什么要有队列，而不是点一下直接下
 *
 * "下载整部剧"一次会排上几十上百集（一部短剧动辄 96 集）。如果并发跑，
 * 会把手机带宽打满、发热、而且失败重试互相干扰。所以：
 *   · **串行**下（一次一集），稳、可预期；
 *   · 队列状态对界面可见（离线页能看到"还有几集在排队/在下"）；
 *   · 用户离开详情页、甚至切到别的 Tab，下载继续 —— 所以队列的生命周期
 *     挂在 App 级别（[YanhuoRoot]）而不是某个页面。
 *
 * # 与 [DownloadStore] 的分工
 *
 * 本类只管"正在下什么、进度多少"（内存态）；下完之后把结果交给
 * [DownloadStore] 落盘记账。分开的好处：进程被杀掉时，已经下完的集
 * 记录都在，未下完的集文件还在（下次重试会跳过已下好的分片）。
 */
class DownloadQueue(private val scope: CoroutineScope) {

    private companion object {
        const val TAG = "DownloadQueue"
    }

    /** 一集的任务。 */
    data class Task(
        val drama: Drama,
        val episode: Episode,
        val done: Int = 0,
        val total: Int = 0,
        val failed: Boolean = false,
    ) {
        val id: String get() = "${drama.key}#${episode.index}"
        /** 0f..1f；总片数未知时给 0（界面显示"准备中"）。 */
        val progress: Float get() = if (total > 0) done.toFloat() / total else 0f
    }

    private val _queue = MutableStateFlow<List<Task>>(emptyList())
    /** 排队 + 正在下的任务（先进先出）。 */
    val queue: StateFlow<List<Task>> = _queue.asStateFlow()

    private val _current = MutableStateFlow<Task?>(null)
    /** 正在下载的那一集（null = 空闲）。 */
    val current: StateFlow<Task?> = _current.asStateFlow()

    private var worker: Job? = null

    /** 已下好、待界面刷新的通知计数（离线页监听它重新拉一遍列表）。 */
    private val _finishedCount = MutableStateFlow(0)
    val finishedCount: StateFlow<Int> = _finishedCount.asStateFlow()

    val busy: Boolean get() = _current.value != null || _queue.value.isNotEmpty()

    /**
     * 入队。已在队列里或已下载过的会跳过（避免重复点"下载整部"排一堆重复任务）。
     *
     * @param episodes 要下的集（单集就传一个）
     * @return 实际入队的数量
     */
    fun enqueue(drama: Drama, episodes: List<Episode>): Int {
        if (episodes.isEmpty()) return 0
        val existing = (_queue.value + listOfNotNull(_current.value)).map { it.id }.toSet()
        val downloaded = DownloadStore.downloadedEpisodes(drama.key)
        val fresh = episodes
            .filter { it.playable }
            // 匹配键必须和 Task.id 的拼法完全一致（"剧key#集号"），
            // 否则去重失效，重复点"下载整部"会排进一堆重复任务
            .filter { "${drama.key}#${it.index}" !in existing }
            .filter { it.index !in downloaded }
            .map { Task(drama = drama, episode = it) }
        if (fresh.isEmpty()) {
            Diag.info(TAG, "无需入队（都已下载或已在队列）")
            return 0
        }
        _queue.value = _queue.value + fresh
        Diag.info(TAG, "入队 ${fresh.size} 集《${drama.title}》")
        pump()
        return fresh.size
    }

    /** 取消排队中但还没开始的任务。**不影响**正在下的那一集（下完它再停）。 */
    fun cancelQueued() {
        val n = _queue.value.size
        _queue.value = emptyList()
        Diag.info(TAG, "已取消排队中的 $n 集")
    }

    /** 当前这一集下完就停：把队列清空，并标记让 worker 退出。 */
    fun stop() {
        cancelQueued()
        stopFlag = true
    }

    @Volatile
    private var stopFlag = false

    private fun pump() {
        if (worker?.isActive == true) return
        worker = scope.launch {
            stopFlag = false
            while (_queue.value.isNotEmpty()) {
                if (stopFlag) break
                val task = _queue.value.first()
                _queue.value = _queue.value.drop(1)
                _current.value = task
                runOne(task)
                _current.value = null
            }
            Diag.info(TAG, "下载队列已清空")
        }
    }

    private suspend fun runOne(task: Task) {
        val res = OfflineDownloader.downloadEpisode(
            drama = task.drama,
            episode = task.episode,
        ) { done, total ->
            // 更新当前任务的进度（只改当前这一条，别动队列整体）
            _current.value = _current.value
                ?.takeIf { it.id == task.id }
                ?.copy(done = done, total = total)
        }

        if (res.isSuccess) {
            val bytes = OfflineDownloader.episodeBytes(task.drama, task.episode.index)
            DownloadStore.add(task.drama, task.episode, bytes)
            _finishedCount.value += 1        } else {
            // 失败不中断整个队列 —— 一部剧里某一集源上缺失很正常，
            // 不能因为它把后面 90 集都卡住。失败信息记日志，用户可在离线页看到缺哪集。
            Diag.warn(
                TAG,
                "第 ${task.episode.index} 集下载失败，继续下一集：" +
                    "${res.exceptionOrNull()?.message?.take(60)}",
            )
        }
    }
}
