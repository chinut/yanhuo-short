package com.chinut.yanhuo.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.FormatListNumbered
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.chinut.yanhuo.core.Diag
import com.chinut.yanhuo.data.Drama
import com.chinut.yanhuo.data.DramaDetail
import com.chinut.yanhuo.data.Episode
import com.chinut.yanhuo.core.Prefs
import com.chinut.yanhuo.data.SourceRegistry
import com.chinut.yanhuo.data.UserStore
import com.chinut.yanhuo.ui.components.PillButton
import com.chinut.yanhuo.ui.theme.Dim
import com.chinut.yanhuo.ui.theme.Ink
import com.chinut.yanhuo.ui.theme.Txt
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/** 播放地址解析状态。 */
private sealed interface StreamState {
    data object Idle : StreamState
    data object Loading : StreamState

    /** 就绪。[url] 是可直接播放的地址。 */
    data class Ready(val url: String) : StreamState

    data class Failed(val message: String) : StreamState
}

/**
 * 上下滑切集时，手指抬起后该切到哪一集。
 *
 * 抽成纯函数是为了**能写单元测试**：手势本身没法在测试里模拟，
 * 但"位移多少算切、到头了不切"这两个判定是纯逻辑，必须测到 ——
 * 边界写错会导致"第 1 集还往下滑"或"最后一集还往上滑"这类 bug。
 *
 * @param dy 累计纵向位移（负=上滑=下一集，正=下滑=上一集）
 * @param threshold 触发阈值（一般取屏高的 1/3）
 * @param current 当前集号
 * @param total 总集数
 * @return 目标集号；不需要切集时返回 0
 */
internal fun resolveSwipeTarget(dy: Float, threshold: Float, current: Int, total: Int): Int {
    if (threshold <= 0f) return 0
    // 上滑 → 下一集
    if (dy <= -threshold) {
        val next = current + 1
        return if (next <= total) next else 0
    }
    // 下滑 → 上一集
    if (dy >= threshold) {
        val prev = current - 1
        return if (prev >= 1) prev else 0
    }
    return 0
}

/**
 * 播放器。**竖屏全屏**。
 *
 * # 需求对应的四件事，各自在哪
 *
 * | 需求 | 实现位置 |
 * |---|---|
 * | **播放器自动联播** | 监听 `STATE_ENDED` → 非最后一集就切下一集（[switchEpisode]） |
 * | **断点续播** | 进来自动 seek 到上次位置；播放中每 10 秒 + 暂停/切集/退出时存进度 |
 * | **视频收藏** | 右上角心形按钮，写入 [UserStore] |
 * | **连载订阅** | 详情页订阅；这里负责"看过了就清掉更新提示" |
 *
 * # 为什么切集还要走一次解析
 *
 * 七猫的详情接口**一次就把全集直链给全了**，所以正常情况下切集是纯内存操作，
 * 不会再发请求。但仍然保留 [SourceRegistry.resolve] 这一步：地址带时效，
 * 而且以后接入"点一集取一集"的源时，这里不用改。
 * 一直不落盘也是这个原因 —— 存下来的地址会过期，下次启动就是一堆死链。
 *
 * # 为什么不用 media3 自带的控制器
 *
 * 默认控制器是**横屏**设计（按钮一排、进度条在底部居中），样式和本 App
 * 完全不搭，而且在小屏竖屏手机上按钮偏小。所以 `useController = false`，
 * 自己画一套：进度条贴底、按钮 40dp 起步、选集走 5 列网格。
 */
@Composable
fun PlayerScreen(
    initialDrama: Drama,
    initialDetail: DramaDetail,
    initialIndex: Int,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ---------- 剧集 / 当前集 ----------
    var drama by remember { mutableStateOf(initialDrama) }
    var detail by remember { mutableStateOf(initialDetail) }
    var index by remember {
        mutableIntStateOf(
            initialIndex.coerceIn(1, initialDetail.episodes.size.coerceAtLeast(1)),
        )
    }

    // ---------- 播放器状态 ----------
    var stream by remember { mutableStateOf<StreamState>(StreamState.Idle) }
    var playing by remember { mutableStateOf(false) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var bufferedMs by remember { mutableLongStateOf(0L) }
    var showControls by remember { mutableStateOf(true) }
    var showEpisodes by remember { mutableStateOf(false) }
    var isFav by remember { mutableStateOf(false) }
    var resumeRow by remember { mutableStateOf(UserStore.progress(initialDrama.key)) }

    // ---------- ExoPlayer ----------
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            playWhenReady = true
            setHandleAudioBecomingNoisy(true)
        }
    }

    // 退出时彻底释放。**必须**做 —— 否则返回首页后播放器还在后台解码，
    // 手机发热掉电，且再次进入时两个实例抢音频焦点。
    DisposableEffect(Unit) {
        onDispose {
            runCatching {
                player.stop()
                player.release()
            }
            Diag.info("Player", "播放器已释放")
        }
    }

    // ---------- 播放期间禁止自动锁屏 ----------
    //
    // 短剧一集 1~3 分钟，而系统默认 30 秒无操作就息屏 ——
    // 用户正看着画面突然黑掉，是明确的 bug。
    //
    // 用 `FLAG_KEEP_SCREEN_ON` 而不是 `PowerManager.WakeLock`：
    //   · 这个 flag 由 WindowManager 管理，**窗口不可见时系统自动清除**，
    //     忘了释放也不会漏电；
    //   · WakeLock 要自己配对 acquire/release，漏一次就是持续耗电，
    //     而且需要 WAKE_LOCK 权限。
    //
    // 这里 onDispose 仍然显式清一次，理由是"退出播放器但不退出 App"的场景下
    // 窗口还在，flag 不会自动清 —— 不清的话首页也会一直亮着。
    val view = LocalView.current
    DisposableEffect(view) {
        val window = view.context.findActivity()?.window
        runCatching { window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        onDispose {
            runCatching {
                window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    // ---------- 关键状态引用 ----------
    //
    // ⚠️ 这里必须用 rememberUpdatedState。
    // 播放器监听器是在 DisposableEffect 里注册的**一次**，闭包会永久捕获注册那一刻的
    // index / position 值。直接读 `index` 会永远是初始值 ——
    // 结果就是"自动联播每次都回到第 2 集，无限循环"。
    val indexRef = rememberUpdatedState(index)
    val positionRef = rememberUpdatedState(positionMs)
    val durationRef = rememberUpdatedState(durationMs)
    val detailRef = rememberUpdatedState(detail)
    val dramaRef = rememberUpdatedState(drama)

    /** 存进度。 */
    suspend fun saveProgress(force: Boolean) {
        val d = dramaRef.value
        val det = detailRef.value
        val idx = indexRef.value
        val ep = det.episodes.firstOrNull { it.index == idx } ?: return
        val dur = durationRef.value
        val pos = positionRef.value
        if (dur <= 0L) return
        if (!force && pos < UserStore.MIN_POSITION_MS) return
        UserStore.saveProgress(
            drama = d,
            episodeIndex = ep.index,
            episodeName = ep.name,
            positionMs = pos,
            durationMs = dur,
        )
    }

    /**
     * 切集（自动联播和手动选集走同一条路）。
     *
     * ⚠️ 先 `stop()` 再换 index：两个 player 同时活着在老设备上会直接黑屏。
     */
    fun switchEpisode(target: Int) {
        val det = detailRef.value
        if (target < 1 || target > det.episodes.size) {
            Diag.info("Player", "已经是边界（共 ${det.episodes.size} 集），不再切换")
            return
        }
        scope.launch { saveProgress(force = true) }
        runCatching { player.stop() }
        index = target
    }

    /**
     * 一集播完。
     *
     * 这是"自动联播"的落点：短剧一集只有 1~2 分钟，能不能自动接上
     * 直接决定这个 App 好不好用。
     */
    fun handleEnded() {
        scope.launch { saveProgress(force = true) }
        if (!Prefs.autoNext) {
            Diag.info("Player", "自动联播已关闭，停在结尾")
            return
        }
        val det = detailRef.value
        val cur = indexRef.value
        if (cur >= det.episodes.size) {
            Diag.info("Player", "最后一集播完（共 ${det.episodes.size} 集），停在结尾")
            return
        }
        Diag.info("Player", "第 $cur 集播完 → 自动播第 ${cur + 1} 集")
        switchEpisode(cur + 1)
    }

    // ---------- 横版视频 → 横屏播放 ----------
    //
    // ⚠️ 这两个状态必须声明在 `DisposableEffect(player)` **之前**：
    // 播放器监听的闭包在注册那一刻就捕获了它们，声明在后面会编译不过
    // （Unresolved reference），或者捕获到错误的实例。
    //
    // 尺寸取自 onVideoSizeChanged，是**解码后的视频尺寸**，不是控件尺寸 ——
    // 控件永远是全屏的，靠它判断不出横竖版。
    var videoW by remember { mutableIntStateOf(0) }
    var videoH by remember { mutableIntStateOf(0) }
    var landscape by remember { mutableStateOf(false) }

    // ---------- 播放器监听 ----------
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) handleEnded()
            }

            override fun onPlayerError(error: PlaybackException) {
                Diag.error(
                    "Player",
                    "播放出错：${error.errorCodeName} ${error.message?.take(80)}",
                )
                stream = StreamState.Failed("这一集播放失败了（${error.errorCodeName}）")
            }

            /**
             * 记录视频真实宽高 —— 用来判断"这是不是横版视频"。
             *
             * 为什么需要它：短剧里混着横版（16:9）和竖版（9:16）两种画幅。
             * 竖版在竖屏手机上正好铺满，横版挤在中间一条、上下大片黑边，
             * 这时候给一个"横屏播放"按钮才有意义。
             *
             * 注意拿的是**解码后的视频尺寸**，不是控件尺寸 ——
             * 控件永远是全屏的，靠它判断不出来。
             */
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    videoW = videoSize.width
                    videoH = videoSize.height
                }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // ---------- 解析地址并起播 ----------
    //
    // 断点续播只对"本次进入的第一集"生效 —— 手动点到第 10 集时当然要从头播，
    // 否则用户会莫名其妙从中间开始。
    var restoredOnce by remember { mutableStateOf(false) }

    LaunchedEffect(drama.key, index) {
        val det = detailRef.value
        val ep = det.episodes.firstOrNull { it.index == index } ?: return@LaunchedEffect

        stream = StreamState.Loading
        positionMs = 0L
        durationMs = 0L
        bufferedMs = 0L

        val resolved = runCatching { SourceRegistry.resolve(drama, ep) }.getOrNull()
        if (resolved == null) {
            stream = StreamState.Failed("取不到这一集的播放地址。")
            Diag.warn("Player", "第 $index 集解析失败《${drama.title}》")
            return@LaunchedEffect
        }

        Diag.info("Player", "第 $index 集就绪")
        stream = StreamState.Ready(resolved.url)

        player.setMediaItem(MediaItem.fromUri(resolved.url))
        player.prepare()

        // ---------- 断点续播 ----------
        if (!restoredOnce && Prefs.resumeLastPosition) {
            restoredOnce = true
            val saved = UserStore.progress(drama.key)
            if (saved != null && saved.episodeIndex == index && saved.positionMs > 5_000L) {
                // 等播放器把时长读出来再 seek：太早 seek 会被忽略（时长还是 0）
                var waited = 0
                while (waited < 4_000 && player.duration <= 0L) {
                    delay(100)
                    waited += 100
                }
                val safeMax = (player.duration - 3_000L).coerceAtLeast(0L)
                val target = saved.positionMs.coerceAtMost(safeMax)
                if (target > 0L) {
                    player.seekTo(target)
                    Diag.info("Player", "断点续播 → ${target / 1000} 秒")
                }
            } else if (saved != null && saved.episodeIndex != index) {
                Diag.info("Player", "上次看到第 ${saved.episodeIndex} 集，本次从第 $index 集开始")
            }
        }
    }

    // ---------- 进度轮询 ----------
    LaunchedEffect(player) {
        while (isActive) {
            runCatching {
                positionMs = player.currentPosition.coerceAtLeast(0L)
                durationMs = player.duration.coerceAtLeast(0L)
                bufferedMs = player.bufferedPosition.coerceAtLeast(0L)
            }
            delay(400)
        }
    }

    // ---------- 定期存进度（防"看到一半被杀进程"） ----------
    LaunchedEffect(drama.key, index) {
        while (isActive) {
            delay(10_000)
            saveProgress(force = false)
        }
    }

    // ---------- 控制条自动隐藏 ----------
    LaunchedEffect(showControls, playing) {
        if (showControls && playing) {
            delay(4_000)
            showControls = false
        }
    }

    // ---------- 收藏状态 / 清更新提示 ----------
    LaunchedEffect(Unit) {
        UserStore.ensureLoaded()
        isFav = UserStore.isFavorite(drama.key)
        // 打开就说明用户看到了更新 → 清掉"已更新"标记
        UserStore.clearUpdate(drama.key)
        resumeRow = UserStore.progress(drama.key)
    }

    // ---------- 调试指令（仅 debug 包） ----------
    //
    // 存在的意义：验证"跳到第 N 集能不能播"这类行为时，靠 adb 按坐标点
    // 选集面板非常不可靠（实测点偏过好几次）。有这条命令就能一步到位：
    //   adb shell am broadcast -a com.chinut.yanhuo.DEBUG \
    //       --es cmd play_episode --ei index 4
    val debugCmd by com.chinut.yanhuo.core.DebugBus.commands.collectAsState()
    LaunchedEffect(debugCmd.seq) {
        when (debugCmd.cmd) {
            com.chinut.yanhuo.core.DebugBus.Cmd.PLAY_EPISODE -> {
                if (debugCmd.index > 0) {
                    showEpisodes = false
                    switchEpisode(debugCmd.index)
                }
            }
            com.chinut.yanhuo.core.DebugBus.Cmd.OPEN_EPISODES -> showEpisodes = true
            else -> Unit
        }
    }

    BackHandler(enabled = true) {
        when {
            showEpisodes -> showEpisodes = false
            else -> {
                scope.launch { saveProgress(force = true) }
                onBack()
            }
        }
    }

    // ==================== 画面 ====================

    // ---------- 上下滑切集（像刷视频那样） ----------
    //
    // # 为什么需要"方向锁定"
    //
    // 上下滑切集和左右滑快进**不能各挂一个 pointerInput** ——
    // 两个手势检测器挂在同一节点上会互相争抢指针事件（本项目在进度条上
    // 已经踩过一次：tap 检测器抢走手势导致"拖不动，只能点一下跳一下"）。
    //
    // 所以这里只挂**一个** `detectDragGestures`，并自己判断方向：
    //   · 第一次明显移动的方向决定这次手势干什么（之后不再改变）
    //   · 上下 → 切上一集/下一集
    //   · 左右 → 快退/快进
    //
    // # 为什么用屏幕高度的比例而不是固定位移
    //
    // 固定 160dp 在小屏上太远、在大屏上太近。用 1/3 屏高更符合手感。
    var dragTotal by remember { mutableFloatStateOf(0f) }
    var dragAxis by remember { mutableIntStateOf(0) }          // 0 未定 / 1 上下 / 2 左右
    var dragDx by remember { mutableFloatStateOf(0f) }
    var dragDy by remember { mutableFloatStateOf(0f) }

    // 横屏相关的状态见上方（必须声明在播放器监听之前）

    /**
     * 切换横/竖屏。
     *
     * 为什么用 `requestedOrientation` 而不是别的方式：
     * Manifest 里已经声明了
     * `configChanges="orientation|screenSize|screenLayout|smallestScreenSize"`，
     * 所以旋转**不会重建 Activity** —— 播放器实例、播放进度、当前集都不受影响，
     * 画面只是转过来。这是这个方案能成立的关键前提。
     *
     * 同时隐/显状态栏与导航栏：横屏看剧时那两条会挡住画面。
     */
    fun setLandscape(on: Boolean) {
        landscape = on
        val act = context.findActivity() ?: return
        act.requestedOrientation = if (on) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        // 用 androidx 的兼容封装而不是 android.view 的原始 API：
        //   1. 原始的 `WindowInsets.Type.statusBars()` 在 API < 30 上不存在；
        //   2. 原始类与本文件里 Compose 的 WindowInsets 同名，会 import 冲突。
        val w = act.window ?: return
        val c = WindowCompat.getInsetsController(w, w.decorView)
        if (on) {
            c.hide(WindowInsetsCompat.Type.statusBars())
            c.hide(WindowInsetsCompat.Type.navigationBars())
            // 允许用户从边缘划出系统栏（否则横屏下退不出去）
            c.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            c.show(WindowInsetsCompat.Type.statusBars())
            c.show(WindowInsetsCompat.Type.navigationBars())
        }
    }

    // 离开播放器时必须转回竖屏并恢复系统栏。
    // 否则首页会跟着横过来、状态栏也一直藏着 —— 这是最容易漏的一步。
    DisposableEffect(Unit) {
        onDispose {
            val act = context.findActivity()
            act?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            act?.window?.let { w ->
                val c = WindowCompat.getInsetsController(w, w.decorView)
                c.show(WindowInsetsCompat.Type.statusBars())
                c.show(WindowInsetsCompat.Type.navigationBars())
            }
        }
    }

    // 切集成功后把滑动位移归零。
    //
    // 有这一句，"上滑切下一集"的动画才完整：手指抬起时画面停在偏移位置，
    // 紧接着 index 变化触发这里复位，视觉上就是"旧的一集被推走、新的一集归位"。
    LaunchedEffect(index) { dragTotal = 0f }

    /** 切集提示文案（非空时显示浮层）。 */
    val switchHint: String = run {
        val det = detailRef.value
        val to = run {
            if (dragAxis != 1) 0
            else if (dragDy < 0) index + 1
            else index - 1
        }
        val ep = det.episodes.firstOrNull { it.index == to } ?: return@run ""
        if (to > index) "下一集 · ${ep.name}" else "上一集 · ${ep.name}"
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        // ---------- 视频 ----------
        //
        // 单独包一层 Box：滑动时只让**这一层**跟手上移/下移，
        // 控制条（在下面那个层级）保持不动。
        // 索引进 key，切集成功后偏移量自动回到 0，卡片的"推进"效果就出来了。
        Box(
            Modifier
                .fillMaxSize()
                .offset {
                    IntOffset(0, dragTotal.takeIf { dragAxis == 1 }?.toInt() ?: 0)
                },
        ) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        // 完整显示：等比缩放，整帧可见。
                        //
                        // 绝不用 RESIZE_MODE_ZOOM —— 它是"放大铺满、裁掉溢出"，
                        // 会横屏剧裁成一条、竖屏剧削掉字幕。宁可留黑边。
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        this.player = player
                    }
                },
                update = {
                    it.player = player
                    // 每次 update 都重申一次，防止别处改动残留（幂等，无副作用）
                    it.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                },
                modifier = Modifier
                    .fillMaxSize()
                    // 拖动：上下切集 / 左右快进。方向由**第一次明显移动**决定，
                    // 一旦锁定就不再改变（见上方注释）。
                    .pointerInput(Unit) {
                        // 触发方向锁定所需的位移：和系统的触摸阈值对齐，
                        // 太小学生会误判成"想切集"，太大又显得迟钝
                        val slop = 12f * density
                        detectDragGestures(
                            onDragStart = {
                                dragTotal = 0f
                                dragAxis = 0
                                dragDx = 0f
                                dragDy = 0f
                            },
                            onDragEnd = {
                                if (dragAxis == 1) {
                                    // 拖够 1/3 屏高才真的换集，否则算"没想换"，弹回去。
                                    // 判定逻辑抽在 resolveSwipeTarget 里，有单元测试覆盖
                                    val target = resolveSwipeTarget(
                                        dy = dragDy,
                                        threshold = size.height / 3f,
                                        current = indexRef.value,
                                        total = detailRef.value.episodes.size,
                                    )
                                    if (target > 0) switchEpisode(target)
                                    // 无论切没切，偏移都归零。
                                    // 切集时 index 变了 → LaunchedEffect 也会做一次，
                                    // 两处重复设置同一个值是无害的（幂等）
                                    dragTotal = 0f
                                    dragAxis = 0
                                }
                                dragDx = 0f
                                dragDy = 0f
                            },
                            onDragCancel = {
                                dragTotal = 0f
                                dragAxis = 0
                                dragDx = 0f
                                dragDy = 0f
                            },
                        ) { change, dragAmount ->
                            change.consume()
                            dragDx += dragAmount.x
                            dragDy += dragAmount.y

                            if (dragAxis == 0) {
                                when {
                                    abs(dragDy) > slop && abs(dragDy) >= abs(dragDx) -> {
                                        dragAxis = 1
                                        showControls = false
                                    }
                                    abs(dragDx) > slop -> dragAxis = 2
                                }
                            }

                            when (dragAxis) {
                                // 上下：整块画面上/下挪。边界处阻尼到 30%，
                                // 手感上"拉不动了"，比硬顶住更清楚
                                1 -> {
                                    val det = detailRef.value
                                    val cur = indexRef.value
                                    val up = dragDy < 0
                                    val hasNeighbor = if (up) cur + 1 <= det.episodes.size else cur - 1 >= 1
                                    val limit = size.height * 0.45f
                                    val damped = if (hasNeighbor) dragDy else dragDy * 0.3f
                                    dragTotal = damped.coerceIn(-limit, limit)
                                }

                                // 左右：快退/快进。整屏宽度 ≈ 90 秒
                                2 -> {
                                    val delta = (dragAmount.x / size.width) * 90_000
                                    runCatching {
                                        player.seekTo(
                                            (player.currentPosition + delta.toLong())
                                                .coerceAtLeast(0L),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    // 单击显隐控制条。
                    //
                    // ⚠️ 这里**故意不做**长按切换「铺满」。曾经加过一个
                    // ZOOM/ZOOM 切换，但它会裁画面 —— 用户反馈"完全不可用"，
                    // 所以彻底移除了缩放这条路，只保留"完整显示"。
                    // 宁可留黑边，也不能让人看不全。
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                    ) { showControls = !showControls },
            )
        }

        // 切集提示（浮在画面上，随位移淡入）
        if (switchHint.isNotBlank()) {
            val reached = abs(dragTotal) >= 60f

            Box(
                Modifier
                    .align(if (dragDy < 0) Alignment.BottomCenter else Alignment.TopCenter)
                    .padding(
                        bottom = if (dragDy < 0) 140.dp else 0.dp,
                        top = if (dragDy < 0) 0.dp else 140.dp,
                    ),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Dim.Gap6),
                    modifier = Modifier
                        .background(
                            if (reached) Ink.Brand else Color.Black.copy(alpha = 0.6f),
                            RoundedCornerShape(Dim.RadiusXl),
                        )
                        .padding(horizontal = Dim.Gap16, vertical = Dim.Gap8),
                ) {
                    Icon(
                        imageVector = if (dragDy < 0) {
                            Icons.Rounded.KeyboardArrowUp
                        } else {
                            Icons.Rounded.KeyboardArrowDown
                        },
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = switchHint,
                        color = Color.White,
                        fontSize = Txt.Caption,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }

        // ---------- 加载中 ----------
        if (stream is StreamState.Loading) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Ink.Brand, strokeWidth = 3.dp)
                    Spacer(Modifier.height(Dim.Gap12))
                    Text("正在取播放地址…", color = Ink.TextDim, fontSize = Txt.Caption)
                }
            }
        }

        // ---------- 播放失败 ----------
        (stream as? StreamState.Failed)?.let { fail ->
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.78f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    Modifier.padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = fail.message,
                        color = Ink.Text,
                        fontSize = Txt.Subtitle,
                        textAlign = TextAlign.Center,
                        lineHeight = 24.sp,
                    )
                    Spacer(Modifier.height(Dim.Gap6))
                    Text(
                        text = "换一集试试，或者到「我的 → 数据源自检」看接口是否正常。",
                        color = Ink.TextFaint,
                        fontSize = Txt.Caption,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(Dim.Gap20))
                    Row(horizontalArrangement = Arrangement.spacedBy(Dim.Gap12)) {
                        PillButton("重试", icon = Icons.Rounded.Replay, filled = true) {
                            val ep = detailRef.value.episodes
                                .firstOrNull { it.index == index } ?: return@PillButton
                            scope.launch {
                                stream = StreamState.Loading
                                val r = runCatching {
                                    SourceRegistry.resolve(drama, ep)
                                }.getOrNull()
                                if (r == null) {
                                    stream = StreamState.Failed("还是取不到这一集的地址")
                                } else {
                                    stream = StreamState.Ready(r.url)
                                    player.setMediaItem(MediaItem.fromUri(r.url))
                                    player.prepare()
                                }
                            }
                        }
                        PillButton("选集", icon = Icons.Rounded.FormatListNumbered) {
                            showEpisodes = true
                        }
                    }
                }
            }
        }

        // ==================== 控制层 ====================
        AnimatedVisibility(
            visible = showControls && stream !is StreamState.Failed,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(Modifier.fillMaxSize()) {
                // 上下渐变，保证文字/按钮在亮画面上也看得清
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                        .align(Alignment.TopCenter)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Black.copy(alpha = 0.72f), Color.Transparent),
                            ),
                        ),
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.82f)),
                            ),
                        ),
                )

                // ---------- 顶栏 ----------
                Column(
                    Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = Dim.PageH, vertical = Dim.Gap8),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircleAction(Icons.AutoMirrored.Rounded.ArrowBack, "返回") {
                            scope.launch { saveProgress(force = true) }
                            onBack()
                        }

                        Spacer(Modifier.width(Dim.Gap12))

                        Column(Modifier.weight(1f)) {
                            Text(
                                text = drama.title,
                                color = Color.White,
                                fontSize = Txt.Subtitle,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = buildString {
                                    append("第 $index 集")
                                    if (drama.episodeCount > 0) {
                                        append(" / 共 ${drama.episodeCount} 集")
                                    }
                                    // 这里原来会追加内容出处（"· 某某短剧"）。
                                    // App 只有一个源，标出处没有信息量，去掉。
                                },
                                color = Color.White.copy(alpha = 0.72f),
                                fontSize = Txt.Tiny,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        CircleAction(
                            icon = if (isFav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            desc = if (isFav) "取消收藏" else "收藏",
                            tint = if (isFav) Ink.Brand else Color.White,
                        ) {
                            scope.launch {
                                UserStore.toggleFavorite(drama)
                                isFav = UserStore.isFavorite(drama.key)
                            }
                        }

                        // ---------- 横版视频：横屏播放 ----------
                        //
                        // 只在**横版视频**（宽 > 高）时出现。竖版视频在竖屏手机上
                        // 本来就铺得满，给按钮只会添乱。
                        //
                        // 用文字而不是图标：项目只依赖 material-icons-core，
                        // 里面没有旋转类图标（ScreenRotation 等在 -extended 里）。
                        // 文字还更直白 —— "横屏"两个字比一个旋转箭头好懂。
                        if (videoW > videoH && videoW > 0) {
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(Dim.RadiusSm))
                                    .background(Ink.Card)
                                    .clickable { setLandscape(!landscape) }
                                    .padding(horizontal = Dim.Gap8, vertical = 6.dp),
                            ) {
                                Text(
                                    text = if (landscape) "竖屏" else "横屏",
                                    color = Color.White,
                                    fontSize = Txt.Tiny,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                    }
                }

                // ---------- 中间：播放 / 暂停 ----------
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.38f))
                        .clickable {
                            if (playing) {
                                player.pause()
                                scope.launch { saveProgress(force = true) }
                            } else {
                                // 播完了再点 → 从头重播，而不是"点了没反应"
                                if (durationMs > 0 && positionMs >= durationMs - 1_000) {
                                    player.seekTo(0)
                                }
                                player.play()
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (playing) "暂停" else "播放",
                        tint = Color.White,
                        modifier = Modifier.size(32.dp),
                    )
                }

                // ---------- 底部 ----------
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(
                            bottom = WindowInsets.navigationBars.asPaddingValues()
                                .calculateBottomPadding() + Dim.Gap12,
                        )
                        .padding(horizontal = Dim.PageH),
                ) {
                    ScrubBar(
                        position = positionMs,
                        duration = durationMs,
                        buffered = bufferedMs,
                        onSeek = { player.seekTo(it) },
                        onSeekFinished = { scope.launch { saveProgress(force = true) } },
                    )

                    Spacer(Modifier.height(Dim.Gap12))

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(Dim.Gap8)) {
                            IconAction(
                                icon = Icons.Rounded.SkipPrevious,
                                desc = "上一集",
                                enabled = index > 1,
                            ) { switchEpisode(index - 1) }

                            IconAction(
                                icon = Icons.Rounded.SkipNext,
                                desc = "下一集",
                                enabled = index < detail.episodes.size,
                            ) { switchEpisode(index + 1) }
                        }

                        PillButton(
                            text = "选集 · $index/${detail.episodes.size}",
                            icon = Icons.Rounded.FormatListNumbered,
                            onClick = { showEpisodes = true },
                        )
                    }
                }
            }
        }

        // ==================== 选集面板 ====================
        if (showEpisodes) {
            EpisodeSheet(
                episodes = detail.episodes,
                current = index,
                resumeIndex = resumeRow?.episodeIndex ?: 0,
                onPick = { switchEpisode(it); showEpisodes = false },
                onDismiss = { showEpisodes = false },
            )
        }
    }
}

/** 圆形小按钮（顶栏用）。 */
@Composable
private fun CircleAction(
    icon: ImageVector,
    desc: String,
    tint: Color = Color.White,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = desc, tint = tint, modifier = Modifier.size(19.dp))
    }
}

/** 圆形次要操作按钮。 */
@Composable
private fun IconAction(
    icon: ImageVector,
    desc: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.35f))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = desc,
            tint = if (enabled) Color.White else Color.White.copy(alpha = 0.3f),
            modifier = Modifier.size(21.dp),
        )
    }
}

/**
 * 进度条。
 *
 * 自己画而不是用 `Slider`：Slider 的滑块有固定最小尺寸、轨道带内边距，
 * 压在视频画面上显得很"重"。这里要的是一条细线，拖动时才变粗。
 */
@Composable
private fun ScrubBar(
    position: Long,
    duration: Long,
    buffered: Long,
    onSeek: (Long) -> Unit,
    onSeekFinished: () -> Unit,
) {
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }

    val shownPosition = if (dragging && duration > 0) {
        (dragFraction * duration).toLong()
    } else {
        position
    }
    val fraction = when {
        dragging -> dragFraction
        duration > 0 -> (position.toFloat() / duration).coerceIn(0f, 1f)
        else -> 0f
    }
    val bufferedFraction =
        if (duration > 0) (buffered.toFloat() / duration).coerceIn(0f, 1f) else 0f

    Column(Modifier.fillMaxWidth()) {
        Text(
            text = "${fmt(shownPosition)} / ${fmt(duration)}",
            color = Color.White.copy(alpha = 0.8f),
            fontSize = Txt.Tiny,
        )
        Spacer(Modifier.height(Dim.Gap4))
        Box(
            Modifier
                .fillMaxWidth()
                .height(24.dp)
                // ⚠️ 只挂**一个** pointerInput。
                //
                // 之前这里挂了两个（`detectTapGestures` + `detectHorizontalDragGestures`）。                // 两个手势检测器挂在同一节点上会互相争抢指针事件：
                // tap 检测器拿到 down 之后就把这次手势"认领"了，
                // 表现是**进度条拖不动，只能点一下跳一下**。
                //
                // 现在合成一个 `detectDragGestures`：
                //   · onDragStart  → "点哪跳哪"（按下即定位）
                //   · onHorizontalDrag → 拖动时实时跟手
                //   · onDragEnd    → 松手才真正 seek
                // 一个检测器，没有争抢，也不用等触摸阈值。
                .pointerInput(duration) {
                    detectDragGestures(
                        onDragStart = {
                            if (duration > 0) {
                                dragging = true
                                dragFraction = (it.x / size.width).coerceIn(0f, 1f)
                            }
                        },
                        onDragEnd = {
                            if (dragging && duration > 0) {
                                onSeek((dragFraction * duration).toLong())
                                onSeekFinished()
                            }
                            dragging = false
                        },
                        onDragCancel = { dragging = false },
                    ) { change, _ ->
                        change.consume()
                        if (duration > 0) {
                            dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                        }
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            // 轨道
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(if (dragging) 4.dp else 2.5.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.25f)),
            )
            // 已缓冲
            Box(
                Modifier
                    .fillMaxWidth(bufferedFraction)
                    .height(2.5.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.35f)),
            )
            // 已播
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(if (dragging) 4.dp else 2.5.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Brush.horizontalGradient(listOf(Ink.BrandGlow, Ink.Brand))),
            )
        }
    }
}

/**
 * 选集面板（底部抽屉）。
 *
 * 用 5 列网格而不是横滑列表：短剧动辄 80~200 集，横滑要划很久；
 * 网格一屏能看到 30 个，配合集数定位很快。
 */
@Composable
private fun EpisodeSheet(
    episodes: List<Episode>,
    current: Int,
    resumeIndex: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Ink.Scrim)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
            ) { onDismiss() },
    ) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(430.dp)
                .clip(RoundedCornerShape(topStart = Dim.RadiusXl, topEnd = Dim.RadiusXl))
                .background(Ink.Base)
                // 吃掉点击，避免点面板内部也把面板关掉
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                ) {},
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(Dim.PageH),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "选集",
                    color = Ink.Text,
                    fontSize = Txt.Subtitle,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(Dim.Gap8))
                Text("共 ${episodes.size} 集", color = Ink.TextFaint, fontSize = Txt.Caption)
                Spacer(Modifier.weight(1f))
                Text(
                    text = "收起",
                    color = Ink.TextDim,
                    fontSize = Txt.Caption,
                    modifier = Modifier.clickable(onClick = onDismiss),
                )
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(5),
                contentPadding = PaddingValues(
                    start = Dim.PageH,
                    end = Dim.PageH,
                    bottom = Dim.BottomBar,
                ),
                horizontalArrangement = Arrangement.spacedBy(Dim.Gap8),
                verticalArrangement = Arrangement.spacedBy(Dim.Gap8),
            ) {
                items(episodes, key = { it.targetId + "#" + it.index }) { ep ->
                    val isCur = ep.index == current
                    val isResume = ep.index == resumeIndex && !isCur
                    Box(
                        Modifier
                            .height(44.dp)
                            .clip(RoundedCornerShape(Dim.RadiusSm))
                            .background(
                                when {
                                    isCur -> Ink.Brand
                                    isResume -> Ink.Accent.copy(alpha = 0.22f)
                                    ep.locked -> Ink.Card
                                    else -> Ink.CardHi
                                },
                            )
                            .clickable { onPick(ep.index) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = ep.index.toString(),
                                color = if (isCur) Color.White else Ink.Text,
                                fontSize = Txt.Body,
                                fontWeight = if (isCur) FontWeight.Bold else FontWeight.Normal,
                            )
                            if (ep.locked) {
                                Icon(
                                    Icons.Rounded.Lock,
                                    contentDescription = null,
                                    tint = Ink.TextFaint,
                                    modifier = Modifier.size(9.dp),
                                )
                            } else if (isResume) {
                                Text("上次", color = Ink.Accent, fontSize = 8.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 毫秒 → `mm:ss` 或 `h:mm:ss`。
 */
private fun fmt(ms: Long): String {
    if (ms <= 0L) return "00:00"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) {
        "%d:%02d:%02d".format(h, m, s)
    } else {
        "%02d:%02d".format(m, s)
    }
}

/**
 * 从 Compose 里拿到的 [Context] 反查 [Activity]。
 *
 * 为什么要反查：Compose 在主题包装、Activity 嵌套等情况下交给我们的
 * 往往是一个 `ContextWrapper`（比如 `ContextThemeWrapper`），
 * 直接 `as Activity` 会抛 `ClassCastException`。所以要顺着 wrapper 往里剥。
 */
private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
