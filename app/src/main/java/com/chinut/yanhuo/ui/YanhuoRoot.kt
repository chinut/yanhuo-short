package com.chinut.yanhuo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chinut.yanhuo.BuildConfig
import com.chinut.yanhuo.core.Diag
import com.chinut.yanhuo.core.Prefs
import com.chinut.yanhuo.data.Drama
import com.chinut.yanhuo.data.DramaDetail
import com.chinut.yanhuo.data.DownloadQueue
import com.chinut.yanhuo.data.DownloadStore
import com.chinut.yanhuo.data.Episode
import com.chinut.yanhuo.data.SourceRegistry
import com.chinut.yanhuo.data.UserStore
import com.chinut.yanhuo.ui.screens.DetailScreen
import com.chinut.yanhuo.ui.screens.FavoritesScreen
import com.chinut.yanhuo.ui.screens.HomeScreen
import com.chinut.yanhuo.ui.screens.HomeState
import com.chinut.yanhuo.ui.screens.OfflineScreen
import com.chinut.yanhuo.ui.screens.PlayerScreen
import com.chinut.yanhuo.ui.screens.ProfileScreen
import com.chinut.yanhuo.ui.screens.SearchScreen
import com.chinut.yanhuo.ui.screens.SubscribesScreen
import com.chinut.yanhuo.ui.screens.SplashOverlay
import com.chinut.yanhuo.ui.theme.Dim
import com.chinut.yanhuo.ui.theme.Ink
import com.chinut.yanhuo.ui.theme.Txt
import com.chinut.yanhuo.update.UpdateChecker
import com.chinut.yanhuo.update.UpdateInstaller
import kotlinx.coroutines.launch

/**
 * 根界面与导航。
 *
 * # 导航结构
 *
 * ```
 * ┌─ 底部导航（4 个 Tab）─────────────┐
 * │  首页   收藏   订阅   我的        │
 * └──────────────────────────────────┘
 *
 * 首页 ──(搜索按钮)──► 搜索页（全屏，无底部导航）
 * 任意 ──(点卡片)────► 详情页（全屏）
 * 详情 ──(播放)──────► 播放器（全屏，横竖屏内容铺满）
 * ```
 *
 * # 为什么播放器/详情/搜索不做成"底部导航里的页面"
 *
 * 这三个都是**任务型界面**（沉浸看内容 / 完成一次搜索），
 * 底下挂着导航栏既占地方又容易误触。所以它们全屏覆盖，
 * 用返回键逐级退回 —— 和所有视频类 App 一致。
 *
 * # 为什么用 AnimatedContent 而不是导航库
 *
 * 只有 4 个平级 Tab + 3 个覆盖层，用不着 `NavHost` 的路由/参数/返回栈
 * （那套还要处理 `SavedStateHandle`、深层链接）。这里状态就是真相，
 * 少一层框架少一类 bug。
 */
@Composable
fun YanhuoRoot() {
    val scope = rememberCoroutineScope()
    val homeState = remember { HomeState(scope) }

    // 下载队列挂在**这里**（App 级），而不是详情页里。
    // 否则用户一下载就退出详情页，队列跟着那个 Composable 一起被取消，
    // 下载会莫名其妙中断。
    val downloadQueue = remember { DownloadQueue(scope) }

    // ---------- 导航状态 ----------
    var tab by remember { mutableIntStateOf(0) }
    var searchOpen by remember { mutableStateOf(false) }
    /** 打开搜索页时预填的词（调试广播用；正常点图标进来是空串）。 */
    var searchSeed by remember { mutableStateOf("") }
    var detail by remember { mutableStateOf<Drama?>(null) }
    var playing by remember { mutableStateOf<Triple<Drama, DramaDetail, Int>?>(null) }

    // 从详情页返回时，列表里的集数可能已经被详情补全了 → 同步一次
    LaunchedEffect(detail) {
        if (detail == null) homeState.syncEpisodeCounts()
    }

    // ---------- 进入时询问是否继续上次播放 ----------
    //
    // # 为什么要问，而不是直接续播
    //
    // 直接自动跳进播放器有两种糟糕情况：
    //   · 用户其实想找别的剧，一进来就被丢进上次那部，还得退出来；
    //   · 上次是"随便点开试看两秒就退了"，这种记录续播体验很差。
    // 所以做成**一次询问**：想继续就一步到位，不想就安静留在首页。
    //
    // # 只问一次
    //
    // 用 `askedResume` 兜住 —— 否则每次从详情页返回、每次重组都会再弹，
    // 变成骚扰。进程内只问一次（重新启动 App 才会再问）。
    var resumeRow by remember { mutableStateOf<UserStore.Row?>(null) }
    var askedResume by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (askedResume) return@LaunchedEffect
        UserStore.ensureLoaded()
        askedResume = true
        // 只在"开了断点续播"且有像样进度时才问。
        // continueWatching 已经过滤掉"看不够 MIN_POSITION_MS"和"已看完"的记录。
        if (Prefs.resumeLastPosition) {
            resumeRow = UserStore.continueWatching(limit = 1).firstOrNull()
        }
    }

    // 收藏/订阅状态变化后，订阅角标要跟着变
    var updateCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(UserStore.rows.value) {
        updateCount = UserStore.updateCount()
    }

    // ---------- 自动升级 ----------
    //
    // 只在**启动时查一次**。不做定时轮询的理由：短剧 App 单次使用就几分钟，
    // 轮询查不到新版还白耗流量；而"启动时查"足够覆盖"用户下次打开"这个场景。
    val context = LocalContext.current
    var updateInfo by remember { mutableStateOf<UpdateChecker.Info?>(null) }
    var updateDownloading by remember { mutableStateOf(false) }
    var updateProgress by remember { mutableFloatStateOf(-1f) }
    var updateError by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val info = UpdateChecker.fetch() ?: return@LaunchedEffect
        val current = BuildConfig.VERSION_CODE
        if (!info.isNewerThan(current)) {
            Diag.info("Update", "已是最新（远端 ${info.versionName}）")
            return@LaunchedEffect
        }
        // 用户点过"稍后"的同版本就不要再弹 —— 否则每次启动都骚扰一次
        if (Prefs.ignoredUpdate == info.versionCode) {
            Diag.info("Update", "用户已忽略 ${info.versionName}，本次不提示")
            return@LaunchedEffect
        }
        Diag.info("Update", "发现新版本 ${info.versionName}（当前 ${BuildConfig.VERSION_NAME}）")
        updateInfo = info
    }

    // ---------- 调试指令：换源 / 直达界面（仅 debug 包） ----------
    //
    // 验证"换源不用改界面"和"图标类控件"时用。命令：
    //   adb shell am broadcast -a com.chinut.yanhuo.DEBUG --es cmd set_source --es source qimao
    //   adb shell am broadcast -a com.chinut.yanhuo.DEBUG --es cmd open_search --es keyword 总裁
    //   adb shell am broadcast -a com.chinut.yanhuo.DEBUG --es cmd open_tab --ei index 2
    val debugCmd by com.chinut.yanhuo.core.DebugBus.commands.collectAsState()
    LaunchedEffect(debugCmd.seq) {
        when (debugCmd.cmd) {
            com.chinut.yanhuo.core.DebugBus.Cmd.SET_SOURCE -> {
                // 目前只有一个源，所以这条命令现在的效果是"重置首页"。
                // 保留它是因为加源之后这里会自动生效，不用再回来补。
                if (debugCmd.source.isNotBlank()) {
                    Prefs.primarySource = debugCmd.source
                    homeState.load(reset = true)
                }
            }

            com.chinut.yanhuo.core.DebugBus.Cmd.OPEN_SEARCH -> {
                searchSeed = debugCmd.keyword
                searchOpen = true
            }

            com.chinut.yanhuo.core.DebugBus.Cmd.OPEN_TAB -> {
                tab = debugCmd.index.coerceIn(0, 3)
                searchOpen = false
                detail = null
                playing = null
            }

            com.chinut.yanhuo.core.DebugBus.Cmd.OPEN_DETAIL -> {
                // 从本地片库里挑一部打开，按需先做一次收藏/订阅动作
                val pick = com.chinut.yanhuo.data.DramaLibrary.load().getOrNull(debugCmd.index)
                    ?: com.chinut.yanhuo.data.DramaLibrary.load().firstOrNull()
                if (pick != null) {
                    when (debugCmd.source) {
                        "fav" -> UserStore.toggleFavorite(pick)
                        "unfav" -> UserStore.removeFavorite(pick.key)
                        "sub" -> UserStore.toggleSubscribe(pick)
                        "unsub" -> UserStore.removeSubscribe(pick.key)
                        else -> Unit
                    }
                    searchOpen = false
                    playing = null
                    detail = pick
                }
            }

            com.chinut.yanhuo.core.DebugBus.Cmd.CHECK_UPDATE -> {
                // 关键点：`--es url` 会临时覆盖清单地址。
                // 这样联调升级流程时不用为了换个测试地址去改代码重编译。
                // keyword 字段复用为 url（见 DebugBus.Command 的注释）
                if (debugCmd.keyword.isNotBlank()) {
                    UpdateChecker.manifestUrl = debugCmd.keyword
                }
                scope.launch {
                    Diag.info("Update", "手动触发检查：${UpdateChecker.manifestUrl}")
                    val info = UpdateChecker.fetch()
                    if (info == null) {
                        Diag.warn("Update", "清单取不到或格式不对")
                        return@launch
                    }
                    // 手动触发时**不受"已忽略"限制** —— 用户主动点的就该给结果
                    Diag.info("Update", "远端 ${info.versionName}（${info.versionCode}）")
                    updateInfo = info
                }
            }

            else -> Unit
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(listOf(Ink.Base, Ink.Deep, Ink.Deep)),
            ),
    ) {
        // ==================== 主界面（Tab + 底部导航） ====================
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Box(Modifier.weight(1f)) {
                AnimatedContent(
                    targetState = tab,
                    transitionSpec = {
                        fadeIn(tween(180)) togetherWith fadeOut(tween(180))
                    },
                    label = "tab",
                ) { target ->
                    when (target) {
                        0 -> HomeScreen(
                            state = homeState,
                            onOpenSearch = { searchOpen = true },
                            onOpenDrama = { detail = it },
                            onOpenTag = { },
                            onOpenSubscribes = { tab = 3 },
                        )

                        1 -> OfflineScreen(
                            queue = downloadQueue,
                            onPlay = { d, idx ->
                                // 离线播放也要有完整分集 —— 从本地记录重建，
                                // 这样"自动联播"在断网时照样能顺着往下走
                                val eps = DownloadStore.downloadedEpisodes(d.key)
                                    .sorted()
                                    .map { i ->
                                        Episode(
                                            targetId = i.toString(),
                                            name = "第 $i 集",
                                            index = i,
                                            url = "",   // 本地播放由 SourceRegistry 解析
                                        )
                                    }
                                if (eps.isNotEmpty()) {
                                    playing = Triple(
                                        d.copy(episodeCount = eps.size),
                                        DramaDetail(d, eps),
                                        idx,
                                    )
                                }
                            },
                        )

                        2 -> FavoritesScreen(onOpenDrama = { detail = it })
                        3 -> SubscribesScreen(onOpenDrama = { detail = it })
                        else -> ProfileScreen(onOpenDrama = { detail = it })
                    }
                }
            }

            BottomBar(
                current = tab,
                updateCount = updateCount,
                onSelect = { tab = it },
            )
        }

        // ==================== 搜索（全屏覆盖） ====================
        if (searchOpen) {
            Box(Modifier.fillMaxSize().background(Ink.Deep)) {
                SearchScreen(
                    initialKeyword = searchSeed,
                    onBack = {
                        searchOpen = false
                        searchSeed = ""
                    },
                    onOpenDrama = { detail = it },
                )
            }
        }

        // ==================== 详情（全屏覆盖） ====================
        detail?.let { d ->
            Box(Modifier.fillMaxSize().background(Ink.Deep)) {
                DetailScreen(
                    drama = d,
                    onBack = { detail = null },
                    queue = downloadQueue,
                    onPlay = { dr, det, idx ->
                        // 详情页保留在下面：从播放器返回时应该回到那部剧的详情，
                        // 而不是一路退回首页（用户会以为"跳走了"）
                        playing = Triple(dr, det, idx)
                    },
                )
            }
        }

        // ==================== 播放器（最上层全屏） ====================
        playing?.let { (d, det, idx) ->
            PlayerScreen(
                initialDrama = d,
                initialDetail = det,
                initialIndex = idx,
                onBack = { playing = null },
            )
        }

        // ==================== 开屏（最上层，播完自己消失） ====================
        SplashOverlay()

        // ==================== 续播询问 ====================
        //
        // 放在最上层、且在开屏之上：开屏只有 1.6 秒，弹窗要在那之后才抢到注意力。
        // 用 AlertDialog（而不是自定义浮层）是为了白拿系统的返回键处理 ——
        // 点返回 = 取消，不会卡住。
        resumeRow?.let { r ->
            AlertDialog(
                onDismissRequest = { resumeRow = null },
                title = { Text("继续上次播放？") },
                text = {
                    Column {
                        Text(
                            text = r.title,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.height(4.dp))
                        val ep = r.episodeName.ifBlank { "第 ${r.episodeIndex} 集" }
                        // 报出"看到哪儿了"，用户才好判断要不要接着看
                        Text(
                            text = "$ep · ${fmtProgress(r.positionMs, r.durationMs)}",
                            color = Ink.TextDim,
                            fontSize = Txt.Caption,
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        resumeRow = null
                        // 直接进这部剧的详情页，不硬跳播放器：
                        // 详情页本身有"继续看第 N 集"按钮，用户还能顺手选集/看简介。
                        // 这样比"直接开播"更可控，也少一层"怎么退回去"的困惑。
                        detail = dramaOfRow(r)
                    }) { Text("继续") }
                },
                dismissButton = {
                    TextButton(onClick = { resumeRow = null }) { Text("不用了") }
                },
            )
        }

        // ==================== 新版本提示 ====================
        //
        // 放在最上层：有新版时用户一进来就能看到，且"去更新"是一步到位。
        // 用 AlertDialog 是为了白拿返回键处理（点返回=稍后再说，不会卡住）。
        updateInfo?.let { info ->
            AlertDialog(
                onDismissRequest = { updateInfo = null },
                title = { Text("发现新版本 ${info.versionName}") },
                text = {
                    Column {
                        if (info.notes.isNotBlank()) {
                            Text(info.notes, fontSize = Txt.Caption, color = Ink.TextDim)
                            Spacer(Modifier.height(6.dp))
                        }
                        when {
                            updateDownloading -> Text(
                                if (updateProgress >= 0f) {
                                    "正在下载… ${(updateProgress * 100).toInt()}%"
                                } else {
                                    "正在下载…"
                                },
                                fontSize = Txt.Caption,
                                color = Ink.Brand,
                            )

                            updateError.isNotBlank() -> Text(
                                updateError,
                                fontSize = Txt.Caption,
                                color = Ink.Brand,
                            )

                            else -> Text(
                                "下载后会自动打开系统安装界面。",
                                fontSize = Txt.Caption,
                                color = Ink.TextFaint,
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = !updateDownloading,
                        onClick = {
                            if (!UpdateInstaller.canInstall(context)) {
                                // 系统不允许装（Android 8+ 的"未知来源"开关没开）
                                // → 直接送用户去开，而不是下载半天才失败
                                updateError = "请先允许「安装未知应用」，再回来点更新"
                                UpdateInstaller.openInstallSettings(context)
                                return@TextButton
                            }
                            scope.launch {
                                updateDownloading = true
                                updateError = ""
                                val r = UpdateInstaller.download(context, info.urls) { p ->
                                    updateProgress = p
                                }
                                updateDownloading = false
                                r.onSuccess {
                                    updateInfo = null
                                    UpdateInstaller.install(context).onFailure { e ->
                                        updateError = "唤起安装失败：${e.message?.take(50)}"
                                    }
                                }.onFailure { e ->
                                    updateError = "下载失败：${e.message?.take(60)}"
                                }
                            }
                        },
                    ) { Text(if (updateDownloading) "下载中…" else "立即更新") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        updateInfo = null
                        // 记下来，本次启动不再提示（否则每次回首页都会被弹一次）
                        Prefs.ignoredUpdate = info.versionCode
                    }) { Text("稍后") }
                },
            )
        }
    }
}

/** 从观看记录还原出一个可用的 [Drama]（详情页只需要 key 里的 sourceId/targetId + 展示字段）。 */
private fun dramaOfRow(r: UserStore.Row) = Drama(
    sourceId = r.key.substringBefore(':'),
    targetId = r.key.substringAfter(':'),
    title = r.title,
    cover = r.cover,
    episodeCount = r.knownEpisodes,
)

/**
 * 把进度说成人话：「看到 03:24」或「已看 62%」。
 *
 * 有时长就报百分比（更直观），没有就报位置，都没有就报"上次的位置"。
 */
private fun fmtProgress(positionMs: Long, durationMs: Long): String = when {
    durationMs > 0 && positionMs > 0 ->
        "已看 ${(positionMs * 100 / durationMs).coerceIn(1, 99)}%"
    positionMs > 0 -> "看到 ${fmtClock(positionMs)}"
    else -> "上次的位置"
}

/** 毫秒 → mm:ss / h:mm:ss */
private fun fmtClock(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

/**
 * 底部导航。
 *
 * 订阅 Tab 上有更新时挂一个数字角标 —— 这是"连载更新提示"的**应用内**部分
 * （另一部分是系统通知，见 work/UpdateWorker）。
 */
@Composable
private fun BottomBar(
    current: Int,
    updateCount: Int,
    onSelect: (Int) -> Unit,
) {
    val items = listOf(
        Triple(Icons.Rounded.Home, "首页", 0),
        Triple(Icons.Rounded.Download, "离线", 1),
        Triple(Icons.Rounded.Favorite, "收藏", 2),
        Triple(Icons.Rounded.Notifications, "订阅", 3),
        Triple(Icons.Rounded.Person, "我的", 4),
    )

    Column {
        // 一条极细的分隔线，让导航栏和内容分开但不显重
        Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.Line))

        Row(
            Modifier
                .fillMaxWidth()
                .background(Ink.Base.copy(alpha = 0.96f))
                .padding(
                    bottom = WindowInsets.navigationBars.asPaddingValues()
                        .calculateBottomPadding(),
                )
                .height(Dim.BottomBar),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEach { (icon, label, idx) ->
                BottomItem(
                    icon = icon,
                    label = label,
                    selected = current == idx,
                    badge = if (idx == 2) updateCount else 0,
                    modifier = Modifier.weight(1f),
                    onClick = { onSelect(idx) },
                )
            }
        }
    }
}

@Composable
private fun BottomItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    badge: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val tint = if (selected) Ink.Brand else Ink.TextFaint

    Box(
        modifier
            .fillMaxHeight()
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box {
                Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(23.dp))
                if (badge > 0) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 10.dp, y = (-6).dp)
                            .clip(CircleShape)
                            .background(Ink.Magenta)
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (badge > 99) "99+" else badge.toString(),
                            color = Color.White,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = label,
                color = tint,
                fontSize = 10.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }
}
