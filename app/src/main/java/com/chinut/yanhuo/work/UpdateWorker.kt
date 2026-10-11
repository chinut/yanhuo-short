package com.chinut.yanhuo.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.Constraints
import androidx.work.NetworkType
import com.chinut.yanhuo.core.Diag
import com.chinut.yanhuo.core.Prefs
import com.chinut.yanhuo.data.Drama
import com.chinut.yanhuo.data.SourceRegistry
import com.chinut.yanhuo.data.UserStore
import java.util.concurrent.TimeUnit

/**
 * 连载更新检查。
 *
 * # 检查什么
 *
 * 对每一部**已订阅**的剧开一次源详情页，拿当前集数。
 * 如果比订阅时的基线大 → 有新集：
 *
 * ```
 * 订阅时：《首辅娇娘》全 69 集      ← 基线记 69
 * 一周后：详情页 episode_cnt = 72   ← 72 > 69 → 有新集，发通知
 *         同时把基线更新到 72，避免下次还提示同一批
 * ```
 *
 * 为什么**只信详情页**：列表页的"全 N 集"是抓取时的快照，可能滞后好几天；
 * 详情页的 `episode_cnt` 才是当前真实值。
 *
 * # 为什么要节流
 *
 * 每个订阅一次请求。订阅 50 部就是 50 次 HTTP —— 在手机上这么干
 * 既慢又费流量，还容易被源站限流。所以：
 *   · 串行 + 每部之间留一点间隔
 *   · 超过 [MAX_CHECKS] 部就只查最近订阅的那些
 */
class UpdateWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!Prefs.notifyUpdates) {
            Diag.info(TAG, "更新通知已关闭，跳过检查")
            return Result.success()
        }

        UserStore.ensureLoaded()
        val subs = UserStore.subscribes()
        if (subs.isEmpty()) {
            Diag.info(TAG, "没有订阅，跳过检查")
            return Result.success()
        }

        // 最近订阅的更可能是用户在追的，优先检查
        val targets = subs.sortedByDescending { it.updatedAt }.take(MAX_CHECKS)
        Diag.info(TAG, "开始检查 ${targets.size} 部订阅（共 ${subs.size} 部）")

        var updated = 0
        var latestTitle = ""

        for (row in targets) {
            val drama = Drama(
                sourceId = row.key.substringBefore(':'),
                targetId = row.key.substringAfter(':'),
                title = row.title,
                cover = row.cover,
                episodeCount = row.knownEpisodes,
            )

            val result = runCatching { SourceRegistry.checkEpisodes(drama) }.getOrNull()
            if (result == null) {
                // ⚠️ 检查失败**不动基线**。
                // 否则一次网络抖动就会把"已更新"永久抹掉 —— 用户再也不会收到提示。
                Diag.warn(TAG, "《${row.title}》检查失败，保留原基线 ${row.knownEpisodes}")
                continue
            }

            val (nowEpisodes, nowTitle) = result
            if (nowEpisodes > row.knownEpisodes) {
                updated++
                latestTitle = nowTitle
                Diag.info(
                    TAG,
                    "《$nowTitle》有新集：${row.knownEpisodes} → $nowEpisodes",
                )
            }
            runCatching {
                UserStore.markChecked(row.key, nowTitle, row.knownEpisodes, nowEpisodes)
            }

            // 串行之间留间隔：别把源站当压测工具
            kotlinx.coroutines.delay(400)
        }

        Prefs.lastUpdateCheck = System.currentTimeMillis()

        if (updated > 0) {
            // 通知里带上剧名（只有一部时能直接说清是哪部）
            UpdateNotifier.notifyUpdates(applicationContext, updated, latestTitle)
        } else {
            Diag.info(TAG, "本次检查没有新集")
            // 没更新就把之前的通知撤掉，避免留一条过期提示
            UpdateNotifier.cancel(applicationContext)
        }

        return Result.success()
    }

    companion object {
        private const val TAG = "UpdateWorker"

        /** 一次最多检查多少部（控制流量与耗时）。 */
        private const val MAX_CHECKS = 40

        /** 周期性任务的名字。用固定名字 + KEEP，避免重复排队。 */
        private const val PERIODIC_NAME = "yanhuo_update_check"
        private const val ONESHOT_NAME = "yanhuo_update_check_now"

        /**
         * 安排后台定期检查。
         *
         * 15 分钟是 WorkManager 允许的**最小周期**。实际上系统会按
         * Doze / 电池优化批处理，一天能跑几次就够用了 ——
         * 短剧更新本来也不是分钟级的事。
         */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateWorker>(6, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()

            runCatching {
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    PERIODIC_NAME,
                    // KEEP：已有的排期不动，避免每次启动都重置计时器
                    ExistingPeriodicWorkPolicy.KEEP,
                    request,
                )
                Diag.info(TAG, "已安排后台更新检查（每 6 小时）")
            }.onFailure { Diag.warn(TAG, "安排后台检查失败：${it.message}") }
        }

        /**
         * 立刻检查一次。
         *
         * 用途：用户刚订阅完想马上知道有没有新集；或者刚打开 App。
         * 用 `REPLACE` 而不是 `KEEP` —— 用户明确要求"现在就查"，
         * 就不该被一个还在排队的旧任务挡住。
         */
        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<UpdateWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            runCatching {
                WorkManager.getInstance(context).enqueueUniqueWork(
                    ONESHOT_NAME,
                    ExistingWorkPolicy.REPLACE,
                    request,
                )
            }.onFailure { Diag.warn(TAG, "立即检查失败：${it.message}") }
        }
    }
}
