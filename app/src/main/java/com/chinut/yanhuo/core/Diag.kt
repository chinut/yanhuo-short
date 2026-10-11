package com.chinut.yanhuo.core

import android.util.Log
import com.chinut.yanhuo.core.Prefs

/**
 * 日志。
 *
 * 取源这条链路上**每一步都可能失败**（DNS、超时、页面改版、付费墙 404），
 * 而它们的用户可见表现完全一样：转圈 / 空白。所以这里强制要求：
 * 失败必须带**具体原因**（异常类名 + 关键字段），不允许只打一句"失败了"。
 *
 * 平时只打 WARN 以上；打开设置里的「详细日志」后连 info 一起输出。
 * 拉日志：`adb logcat -s Yanhuo:I Yanhuo:W Yanhuo:E`
 */
object Diag {

    const val TAG = "Yanhuo"

    fun info(scope: String, msg: String) {
        Log.i(TAG, "[$scope] $msg")
        if (Prefs.verboseLog) remember("I/$scope", msg)
    }

    fun warn(scope: String, msg: String) {
        Log.w(TAG, "[$scope] $msg")
        remember("W/$scope", msg)
    }

    fun error(scope: String, msg: String, t: Throwable? = null) {
        Log.e(TAG, "[$scope] $msg", t)
        remember("E/$scope", "$msg${t?.let { " · ${it.javaClass.simpleName}: ${it.message}" }.orEmpty()}")
    }

    // ==================== 应用内日志（"我的 → 运行日志"） ====================

    private const val MAX_LINES = 200
    private val ring = ArrayDeque<String>(MAX_LINES)

    private fun remember(tag: String, msg: String) {
        synchronized(ring) {
            val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date())
            ring.addLast("$ts $tag $msg")
            while (ring.size > MAX_LINES) ring.removeFirst()
        }
    }

    /** 最近的日志，最新在后。用户可以直接在设置里看到，不必连电脑。 */
    fun recent(): List<String> = synchronized(ring) { ring.toList() }

    fun clear() = synchronized(ring) { ring.clear() }
}
