package com.chinut.yanhuo.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 调试指令总线。
 *
 * # 为什么需要它
 *
 * 有些功能**没法靠"点屏幕"验证**：
 *
 * ```
 * adb shell input tap X Y
 * ```
 * 只能按坐标点，而界面一旦改版坐标就失效。更麻烦的是"自动联播"这类
 * **要等一集播完**才能观察的行为 —— 靠定时脚本碰运气非常不可靠。
 *
 * 所以留一个广播入口，把"跳到第 N 集""打开选集面板"这类动作
 * 变成一条可脚本化的命令：
 *
 * ```powershell
 * # 直接跳到第 10 集（验证"任意一集都能播"）
 * adb shell am broadcast -a com.chinut.yanhuo.DEBUG \
 *     --es cmd play_episode --ei index 10
 *
 * # 打开选集面板
 * adb shell am broadcast -a com.chinut.yanhuo.DEBUG --es cmd open_episodes
 *
 * # 打开搜索页并预填关键词
 * adb shell am broadcast -a com.chinut.yanhuo.DEBUG --es cmd open_search --es keyword 总裁
 *
 * # 立刻检查更新（把清单地址临时指到 --es url，用来联调升级流程）
 * adb shell am broadcast -a com.chinut.yanhuo.DEBUG --es cmd check_update --es url <清单地址>
 * ```
 *
 * # 安全考虑
 *
 * 这个 action 只在 debug 包里注册（见 [enabled]），release 包**完全不注册**，
 * 所以正式用户装到的版本没有任何调试入口。
 * 另外它不接受任何用户数据、不落盘、不联网，最坏情况只是"换了一集"。
 */
object DebugBus {

    const val ACTION = "com.chinut.yanhuo.DEBUG"

    /** 是否启用。release 包里为 false，接收器不会被注册。 */
    val enabled: Boolean get() = com.chinut.yanhuo.BuildConfig.DEBUG

    /** 指令类型。 */
    enum class Cmd {
        NONE,

        /** 播放器：跳到第 [Command.index] 集。 */
        PLAY_EPISODE,

        /** 播放器：打开选集面板。 */
        OPEN_EPISODES,

        /** 切换主源（[Command.source]）。 */
        SET_SOURCE,

        /** 打开搜索页。 */
        OPEN_SEARCH,

        /**
         * 打开某个 Tab（[Command.index]：0 首页 1 收藏 2 订阅 3 我的）。
         *
         * 为什么连这个都要留入口：主界面上的搜索图标、底部 Tab
         * 都是**只有图标没有文字**的控件，`uiautomator dump` 拿不到它们的坐标，
         * `input tap X Y` 只能靠猜 —— 实测猜了三次都没点中。
         * 有一条能直达的命令，验证就变成确定的事。
         */
        OPEN_TAB,

        /**
         * 打开本地片库里第 [Command.index] 部的详情，并按 [Command.source] 指定的动作
         * 操作：`fav` 收藏、`sub` 订阅、`unfav` 取消收藏、`unsub` 取消订阅。
         *
         * 用途：验证"收藏 → 收藏页"和"订阅 → 订阅页/更新提示"这条数据链路。
         * 这两条链路的界面入口在详情页中间（按钮小、位置随简介长度变化），
         * 用坐标点很容易点空。
         */
        OPEN_DETAIL,

        /**
         * 立刻检查更新。
         *
         * 为什么需要：升级流程要"发现新版 → 下载 → 唤起安装"三步才能验完，
         * 而正常触发点是**冷启动**——每验一次都要杀进程重开，很慢。
         * 有这条命令就能随时触发，而且能把清单地址临时指到联调用的地址
         * （[Command.keyword] 复用为 url），不必为了测试改代码里的常量。
         */
        CHECK_UPDATE,
    }

    data class Command(
        val cmd: Cmd = Cmd.NONE,
        val index: Int = 0,
        val source: String = "",
        val keyword: String = "",
        /** 自增序号，让 StateFlow 能感知到"又来了同一条命令"。 */
        val seq: Long = 0L,
    )

    private val _commands = MutableStateFlow(Command())

    /** 界面订阅它来响应调试指令。 */
    val commands: StateFlow<Command> = _commands.asStateFlow()

    private var seq = 0L

    /**
     * 注册接收器。只在 [enabled] 为 true 时调用（见 MainActivity）。
     *
     * ⚠️ 必须用 `RECEIVER_EXPORTED`：adb shell 的 UID 和 App 不同，
     * `NOT_EXPORTED` 会把脚本发来的广播直接丢掉（电视端项目踩过这个坑）。
     * 同时 Android 14 起强制要求显式声明，用裸 `registerReceiver` 会抛
     * `SecurityException` 直接崩。
     */
    fun register(context: Context) {
        if (!enabled) {
            Diag.info("DebugBus", "release 包，调试入口不注册")
            return
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.action != ACTION) return
                val raw = intent.getStringExtra("cmd").orEmpty()
                val cmd = when (raw) {
                    "play_episode" -> Cmd.PLAY_EPISODE
                    "open_episodes" -> Cmd.OPEN_EPISODES
                    "set_source" -> Cmd.SET_SOURCE
                    "open_search" -> Cmd.OPEN_SEARCH
                    "open_tab" -> Cmd.OPEN_TAB
                    "open_detail" -> Cmd.OPEN_DETAIL
                    "check_update" -> Cmd.CHECK_UPDATE
                    else -> Cmd.NONE
                }
                if (cmd == Cmd.NONE) {
                    Diag.warn("DebugBus", "未知指令：$raw")
                    return
                }
                val index = intent.getIntExtra("index", 0)
                val source = intent.getStringExtra("source").orEmpty()
                // keyword 与 url 共用一个槽位：搜索命令用 keyword，检查更新用 url。
                // 两个名字都收，避免调用方记错参数名（踩过一次：传了 --es url
                // 却只读 keyword，结果地址覆盖没生效，检查的还是代码里的默认地址）
                val slot = intent.getStringExtra("url")?.takeIf { it.isNotBlank() }
                    ?: intent.getStringExtra("keyword").orEmpty()
                seq += 1
                _commands.value = Command(cmd, index, source, slot, seq)
                Diag.info("DebugBus", "收到指令 $cmd index=$index source=$source slot=$slot")
            }
        }
        runCatching {
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(ACTION),
                ContextCompat.RECEIVER_EXPORTED,
            )
            Diag.info("DebugBus", "调试入口已注册（debug 包）")
        }.onFailure {
            Diag.warn("DebugBus", "注册失败：${it.javaClass.simpleName} ${it.message}")
        }
    }
}
