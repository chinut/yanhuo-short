package com.chinut.yanhuo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.chinut.yanhuo.core.Diag
import com.chinut.yanhuo.core.Prefs
import com.chinut.yanhuo.data.DramaLibrary
import com.chinut.yanhuo.data.UserStore
import com.chinut.yanhuo.ui.YanhuoRoot
import com.chinut.yanhuo.ui.theme.YanhuoTheme
import com.chinut.yanhuo.work.UpdateWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 唯一的 Activity（竖屏）。
 *
 * # 主题切换的时机
 *
 * `setTheme(R.style.Theme_Yanhuo)` 必须在 `super.onCreate` **之前**调用，
 * 系统才会用普通主题（深色底、无开屏图）绘制第一帧。
 *
 * 在这之前，XML 里给 Activity 配的是 `Theme.Yanhuo.Splash` ——
 * 它的 `windowBackground` 是开屏图。所以启动时序是：
 *
 * ```
 * 点图标 → [系统] windowBackground 画开屏图（进程还没起来）
 *        → onCreate → setTheme(普通主题) → setContent(Compose)
 *        → Compose 第一帧 → 根组件里的 SplashOverlay 接管动画
 * ```
 *
 * 这样任何一帧都不会是纯黑或纯白。
 */
class MainActivity : ComponentActivity() {

    /**
     * 通知权限申请器。
     *
     * 只在用户**主动打开**「有新集时通知我」时才会被触发（见 [requestNotifyPermission]），
     * 不在冷启动时弹 —— 那种时机用户还不知道这功能干嘛用的，只会点拒绝。
     */
    private val notifyPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Diag.info("Main", "通知权限：${if (granted) "已授予" else "被拒绝"}")
            // 无论是否授权，应用内红点都不依赖它，所以这里不需要做任何补偿
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        // ⚠️ 必须在 super.onCreate 之前
        setTheme(R.style.Theme_Yanhuo)
        super.onCreate(savedInstanceState)

        // 内容延伸到状态栏 / 导航栏下面（沉浸式），本 App 自己处理内边距
        WindowCompat.setDecorFitsSystemWindows(window, false)

        Diag.info("Main", "启动 v${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})")

        // 调试广播入口（release 包内部会自动跳过，见 DebugBus.enabled）
        com.chinut.yanhuo.core.DebugBus.register(this)

        // ---------- 一次性初始化（不能阻塞首帧） ----------
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            UserStore.ensureLoaded()
            if (Prefs.notifyUpdates) {
                // 打开 App 就顺手检查一次订阅更新（不依赖周期性任务是否跑过）
                UpdateWorker.runNow(applicationContext)
            }
        }
        UpdateWorker.schedule(applicationContext)

        setContent {
            YanhuoTheme {
                YanhuoRoot()
            }
        }

        // 从通知点进来 → 直接落到"订阅"页
        if (intent?.getBooleanExtra(EXTRA_OPEN_SUBSCRIBES, false) == true) {
            Diag.info("Main", "由更新通知进入")
        }

        maybeRequestNotifyPermission()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    /**
     * 首次启动时**申请**通知权限的条件。
     *
     * 注意：这里只在"用户已经订阅过东西"时才申请 —— 说明他已经用到了
     * 这个功能，此时申请权限是**有上下文**的，用户更容易理解为什么要给。
     * 没有任何订阅的新用户不会被弹框打扰。
     */
    private fun maybeRequestNotifyPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (!Prefs.notifyUpdates) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) return
        if (UserStore.subscribes().isEmpty()) return

        runCatching { notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
    }

    override fun onStop() {
        super.onStop()
        // 进后台时把还没落盘的片库写掉，避免被系统杀掉时丢掉刚抓到的一批内容
        CoroutineScope(Dispatchers.IO).launch { DramaLibrary.flushIfDirty() }
    }

    companion object {
        /** 通知点进来时带的路由标记。 */
        const val EXTRA_OPEN_SUBSCRIBES = "open_subscribes"
    }
}
