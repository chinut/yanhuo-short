package com.chinut.yanhuo.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.chinut.yanhuo.MainActivity
import com.chinut.yanhuo.R
import com.chinut.yanhuo.core.Diag

/**
 * 连载更新通知。
 *
 * # 权限的现实
 *
 * Android 13（API 33）起，发通知需要 `POST_NOTIFICATIONS` 运行时权限。
 * 这个 App **不主动弹权限框** —— 第一次冷启动就弹一个"允许通知吗"
 * 是转化率杀手，而且用户此时还不知道这个功能有什么用。
 *
 * 所以策略是：
 *   · 用户去「我的」里**打开「有新集时通知我」**时，才在那时申请权限
 *   · 没授权就发不出通知，但**应用内红点照样有**（不依赖任何权限）
 *
 * 这也是为什么"连载更新提示"必须是**双通道**：应用内角标 + 系统通知。
 */
object UpdateNotifier {

    private const val TAG = "UpdateNotifier"
    private const val CHANNEL_ID = "yanhuo_updates"

    /** 创建通知渠道。**必须在 Application.onCreate 调用一次** —— 建晚了第一条通知会被丢掉。 */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            val mgr = context.getSystemService(NotificationManager::class.java) ?: return
            if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_updates),
                // DEFAULT 而不是 HIGH：更新提示不是即时消息，不该用横幅打断用户
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.notification_channel_updates_desc)
                enableVibration(false)
            }
            mgr.createNotificationChannel(channel)
            Diag.info(TAG, "通知渠道已创建")
        }.onFailure { Diag.warn(TAG, "创建通知渠道失败：${it.message}") }
    }

    /**
     * 发一条"有 N 部订阅更新了"的通知。
     *
     * 合并成**一条**而不是每部剧一条：用户订阅 10 部剧时，
     * 一次更新弹 10 条通知会让人直接卸载应用。
     */
    fun notifyUpdates(context: Context, count: Int, latestTitle: String) {
        if (count <= 0) return
        ensureChannel(context)

        // 没权限就别硬发（会抛 SecurityException，在部分 ROM 上直接崩）
        val allowed = runCatching {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }.getOrDefault(false)
        if (!allowed) {
            Diag.info(TAG, "通知权限未开，跳过系统通知（应用内红点仍然有效）")
            return
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_SUBSCRIBES, true)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pi = PendingIntent.getActivity(context, 1001, intent, flags)

        val text = if (count == 1) {
            "《$latestTitle》有新集了"
        } else {
            "包括《$latestTitle》在内，共 $count 部有更新"
        }

        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("你订阅的短剧更新了")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, n)
            Diag.info(TAG, "已发通知：$text")
        }.onFailure {
            Diag.warn(TAG, "发通知失败：${it.javaClass.simpleName} ${it.message}")
        }
    }

    fun cancel(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
    }

    /** 固定 id：新的更新通知覆盖旧的，通知栏不会堆一列。 */
    private const val NOTIFICATION_ID = 2001
}
