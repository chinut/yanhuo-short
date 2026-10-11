package com.chinut.yanhuo.core

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * 设置存储。
 *
 * 用 `SharedPreferences` 而不是数据库：这里存的全是**小字段**（布尔开关、字符串、
 * 少量 id 映射）。量大的部分（片库列表、观看进度、收藏）走文件，见 data/ 下的实现。
 *
 * 每个字段写成 `by bool("key", def)`，好处是类型安全 + 默认值集中在一处，
 * 不用到处写 `getBoolean("xxx", false)`，也不会因为 key 拼错而静默失效。
 */
object Prefs {

    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = context.getSharedPreferences("yanhuo", Context.MODE_PRIVATE)
    }

    // ==================== 播放 ====================

    /** 一集播完自动下一集。 */
    var autoNext: Boolean by bool("auto_next", true)

    /** 再次打开时从上次的进度继续。 */
    var resumeLastPosition: Boolean by bool("resume_position", true)

    // 播放画面缩放**不做成配置项**：
    // 曾经有过「竖屏铺满」开关（默认开），实现是 RESIZE_MODE_ZOOM ——
    // 它放大铺满并裁掉溢出部分，结果横屏剧被裁成一条、竖屏剧字幕被切一半，
    // 用户反馈"画面被裁切、完全不可用"。
    // 现在固定 RESIZE_MODE_FIT（等比缩放、整帧可见、可能留黑边）。
    // 宁可留黑边也不能让人看不全，所以**不留这个开关**，免得再被打开。

    // ==================== 连载订阅 ====================

    /** 订阅的剧集有新集时是否发通知。 */
    var notifyUpdates: Boolean by bool("notify_updates", true)

    /** 上次检查订阅更新的时间（毫秒）。 */
    var lastUpdateCheck: Long by long("last_update_check", 0L)

    // ==================== 源 ====================

    /**
     * 当前使用的源 id。
     *
     * 只有**七猫短剧**一个源：实测每部剧都给全集直链（96 集的剧 96 集全有地址），
     * 满足"能全部看"这个硬要求。红果官网（每部剧只免费前 3 集、第 4 集起 404）
     * 已整体移除。
     *
     * 保留这个配置项是为了以后加源时能切换；现在改它没有效果。
     */
    var primarySource: String by str("primary_source", QIMAO)

    /** 源 id 常量，避免散落的字符串字面量拼错。 */
    const val QIMAO = "qimao"

    // ==================== 升级 ====================

    /**
     * 用户点了"稍后"的那个 versionCode。
     *
     * 记下来的目的是**同一个版本只提示一次** —— 否则每次启动都弹，
     * 用户会烦到直接忽略所有更新。出了更高版本号就会重新提示。
     */
    var ignoredUpdate: Int by int("ignored_update", 0)

    // ==================== 调试 ====================

    /** 是否输出详细日志（排查取源失败时用）。 */
    var verboseLog: Boolean by bool("verbose_log", false)

    // ==================== 委托实现 ====================

    private fun bool(key: String, def: Boolean) = object : ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = sp.getBoolean(key, def)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) =
            sp.edit { putBoolean(key, value) }
    }

    private fun long(key: String, def: Long) = object : ReadWriteProperty<Any?, Long> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = sp.getLong(key, def)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Long) =
            sp.edit { putLong(key, value) }
    }

    private fun int(key: String, def: Int) = object : ReadWriteProperty<Any?, Int> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = sp.getInt(key, def)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) =
            sp.edit { putInt(key, value) }
    }

    private fun str(key: String, def: String) = object : ReadWriteProperty<Any?, String> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): String =
            sp.getString(key, def).orEmpty()

        override fun setValue(thisRef: Any?, property: KProperty<*>, value: String) =
            sp.edit { putString(key, value) }
    }

    /** 给"我的"页做缓存清理用。 */
    fun clearAll() = sp.edit { clear() }
}
