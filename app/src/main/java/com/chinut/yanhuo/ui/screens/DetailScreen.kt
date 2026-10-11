package com.chinut.yanhuo.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.NotificationsNone
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chinut.yanhuo.core.Diag
import com.chinut.yanhuo.data.Drama
import com.chinut.yanhuo.data.DramaDetail
import com.chinut.yanhuo.data.DownloadQueue
import com.chinut.yanhuo.data.DownloadStore
import com.chinut.yanhuo.data.Episode
import com.chinut.yanhuo.core.Prefs
import com.chinut.yanhuo.data.SourceRegistry
import com.chinut.yanhuo.data.UserStore
import com.chinut.yanhuo.ui.components.CoverImage
import com.chinut.yanhuo.ui.components.PillButton
import com.chinut.yanhuo.ui.components.TagChip
import com.chinut.yanhuo.ui.theme.Dim
import com.chinut.yanhuo.ui.theme.Ink
import com.chinut.yanhuo.ui.theme.Txt
import kotlinx.coroutines.launch

/**
 * 详情页。
 *
 * # 为什么要单独一页（而不是点卡片直接播）
 *
 * 短剧一集只有 1~2 分钟，用户几乎一定会**连着看**。直接播第 1 集会让
 * 已经看到第 30 集的用户每次都要手动翻回去。详情页承担：
 *
 *   1. **续播入口** —— 有进度时主按钮是"继续看第 N 集"，没有才是"从第 1 集看"
 *   2. **选集** —— 几十上百集需要网格，不是一排按钮
 *   3. **收藏 / 订阅** —— 顺手就能操作，不用先进播放器
 *
 * # 付费集的显示
 *
 * 七猫是全集免费，所以正常情况下所有集都能点。但界面仍然**不假设**这一点：
 * 如果某个源给出的集带 [Episode.locked]，就显示锁图标 —— 但**依然允许点击**，
 * 因为"锁"只是源的标注，实际取地址时可能仍然成功。
 * 这里不假装它们不可播，也不假装一定能播，而是让用户试。
 */
@Composable
fun DetailScreen(
    drama: Drama,
    onBack: () -> Unit,
    onPlay: (Drama, DramaDetail, Int) -> Unit,
    /** 下载队列（App 级持有，见 [com.chinut.yanhuo.data.DownloadQueue]）。 */
    queue: com.chinut.yanhuo.data.DownloadQueue,
) {
    val scope = rememberCoroutineScope()

    var detail by remember { mutableStateOf<DramaDetail?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var isFav by remember { mutableStateOf(false) }

    // 已下载的集号 + "正在下载"标记。
    // downloadedEpisodes 用 finished 计数刷新（下完一集就重读一次），
    // 这样用户停在详情页看着下载进度涨，不用退出去再进来。
    var downloadedEpisodes by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var downloading by remember { mutableStateOf(false) }
    val finishedCount by queue.finishedCount.collectAsState()
    LaunchedEffect(drama.key, finishedCount) {
        DownloadStore.ensureLoaded()
        downloadedEpisodes = DownloadStore.downloadedEpisodes(drama.key)
        downloading = queue.busy
    }
    var isSub by remember { mutableStateOf(false) }
    var resumeIndex by remember { mutableStateOf(0) }

    BackHandler(enabled = true) { onBack() }

    LaunchedEffect(drama.key) {
        UserStore.ensureLoaded()
        isFav = UserStore.isFavorite(drama.key)
        isSub = UserStore.isSubscribed(drama.key)
        resumeIndex = UserStore.progress(drama.key)?.episodeIndex ?: 0

        loading = true
        error = ""
        val d = runCatching { SourceRegistry.detail(drama) }.getOrNull()
        detail = d
        loading = false
        if (d == null) {
            error = "这部剧的分集取不到。可能是网络问题，或源页面改版了。"
            Diag.warn("Detail", "《${drama.title}》详情为空")
        }
        // 订阅的剧被打开了 → 清掉"已更新"提示
        if (isSub) UserStore.clearUpdate(drama.key)
    }

    // 详情里的集数比列表页更权威，用它覆盖显示
    val shown = detail?.drama?.let { d ->
        if (d.episodeCount > drama.episodeCount) d else drama.copy(
            episodeCount = d.episodeCount.coerceAtLeast(drama.episodeCount),
            intro = d.intro.ifBlank { drama.intro },
            tags = d.tags.ifEmpty { drama.tags },
        )
    } ?: drama

    Column(
        Modifier
            .fillMaxSize()
            .background(Ink.Deep)
            .verticalScroll(rememberScrollState()),
    ) {
        // ==================== 顶部大图 ====================
        Box(Modifier.fillMaxWidth().height(230.dp)) {
            CoverImage(
                url = shown.cover,
                title = shown.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            // 从透明到页面底色的渐变，让图慢慢融进下面的内容
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Ink.Deep.copy(alpha = 0.55f),
                            0.5f to Ink.Deep.copy(alpha = 0.35f),
                            1f to Ink.Deep,
                        ),
                    ),
            )
            // 返回
            Box(
                Modifier
                    .statusBarsPadding()
                    .padding(Dim.Gap12)
                    .size(34.dp)
                    .clip(RoundedCornerShape(Dim.Radius))
                    .background(Ink.Deep.copy(alpha = 0.5f))
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "返回",
                    tint = Ink.Text,
                    modifier = Modifier.size(19.dp),
                )
            }
        }

        // ==================== 标题 + 元信息 ====================
        Column(Modifier.padding(horizontal = Dim.PageH)) {
            Text(
                text = shown.title,
                color = Ink.Text,
                fontSize = Txt.Hero,
                fontWeight = FontWeight.Bold,
                lineHeight = 32.sp,
            )

            Spacer(Modifier.height(Dim.Gap8))

            Row(verticalAlignment = Alignment.CenterVertically) {
                // 集数：需求要求"注明集数"，这里用醒目的橙色
                Text(
                    text = if (shown.episodeCount > 0) "全 ${shown.episodeCount} 集" else "连载中",
                    color = Color.White,
                    fontSize = Txt.Caption,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .background(Ink.Brand, RoundedCornerShape(Dim.RadiusSm))
                        .padding(horizontal = Dim.Gap8, vertical = 3.dp),
                )
                if (shown.hot.isNotBlank()) {
                    Spacer(Modifier.width(Dim.Gap8))
                    Text(shown.hot, color = Ink.Accent, fontSize = Txt.Caption)
                }
                // 这里原来还显示源名（"某某短剧"）。App 只有一个内容源，
                // 把供应方名字摆在剧名旁边对用户没有意义，所以整段去掉了。
            }

            if (shown.tags.isNotEmpty()) {
                Spacer(Modifier.height(Dim.Gap8))
                Row(horizontalArrangement = Arrangement.spacedBy(Dim.Gap6)) {
                    shown.tags.take(4).forEach { TagChip(it) }
                }
            }

            // ==================== 主操作 ====================
            Spacer(Modifier.height(Dim.Gap16))
            Row(horizontalArrangement = Arrangement.spacedBy(Dim.Gap8)) {
                val canPlay = detail?.episodes?.isNotEmpty() == true
                val startAt = if (resumeIndex > 0) resumeIndex else 1
                PillButton(
                    text = if (resumeIndex > 0) "继续看 第 $resumeIndex 集" else "从第 1 集开始",
                    icon = Icons.Rounded.PlayArrow,
                    filled = true,
                    enabled = canPlay,
                    onClick = {
                        detail?.let { onPlay(shown, it, startAt) }
                    },
                    modifier = Modifier.weight(1f),
                )
                PillButton(
                    text = if (isFav) "已收藏" else "收藏",
                    icon = if (isFav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    onClick = {
                        scope.launch {
                            UserStore.toggleFavorite(shown)
                            isFav = UserStore.isFavorite(shown.key)
                        }
                    },
                )
                PillButton(
                    text = if (isSub) "已订阅" else "订阅",
                    icon = if (isSub) {
                        Icons.Rounded.NotificationsActive
                    } else {
                        Icons.Rounded.NotificationsNone
                    },
                    onClick = {
                        scope.launch {
                            UserStore.toggleSubscribe(shown)
                            isSub = UserStore.isSubscribed(shown.key)
                        }
                    },
                )
            }

            // ==================== 离线下载 ====================
            //
            // 单独一行而不是挤在上一行：下载是"第二个动作"（先看再说），
            // 而且"下载整部"文案较长，和其它三个按钮挤一行会换行错乱。
            Spacer(Modifier.height(Dim.Gap8))
            val eps = detail?.episodes.orEmpty()
            val total = eps.size
            val doneCount = downloadedEpisodes.size
            Row(verticalAlignment = Alignment.CenterVertically) {
                PillButton(
                    text = when {
                        total == 0 -> "下载整部"
                        doneCount >= total -> "已全部下载"
                        downloading -> "下载中…"
                        else -> "下载整部（$total 集）"
                    },
                    icon = Icons.Rounded.Download,
                    // 全下完了就没必要再点；正在下时也禁用，避免重复入队
                    enabled = total > 0 && doneCount < total && !downloading,
                    onClick = {
                        val n = queue.enqueue(shown, eps)
                        if (n > 0) {
                            downloading = true
                            Diag.info("Detail", "整部下载入队 $n 集《${shown.title}》")
                        }
                    },
                )
                Spacer(Modifier.width(Dim.Gap8))
                Text(
                    text = when {
                        total == 0 -> ""
                        doneCount > 0 -> "已下载 $doneCount / $total 集"
                        else -> "下载后可断网观看"
                    },
                    color = if (doneCount > 0) Ink.Brand else Ink.TextFaint,
                    fontSize = Txt.Tiny,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // ==================== 简介 ====================
            if (shown.intro.isNotBlank()) {
                Spacer(Modifier.height(Dim.Gap16))
                Text("简介", color = Ink.Text, fontSize = Txt.Subtitle, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(Dim.Gap6))
                Text(
                    text = shown.intro,
                    color = Ink.TextDim,
                    fontSize = Txt.Body,
                    lineHeight = 22.sp,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.height(Dim.Gap20))
        }

        // ==================== 选集 ====================
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Dim.PageH),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("选集", color = Ink.Text, fontSize = Txt.Subtitle, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(Dim.Gap8))
            detail?.let { d ->
                if (d.freeCount in 1 until d.total) {
                    // 只有"这个源确实有付费墙"时才出现（当前源不会有）
                    Text(
                        text = "第 1~${d.freeCount} 集可直接播，其余可能取不到地址",
                        color = Ink.TextFaint,
                        fontSize = Txt.Tiny,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else if (d.total > 0) {
                    // 主源（七猫）全集免费 —— 用户最关心的就是这个，明确写出来
                    Text(
                        text = "全 ${d.total} 集免费",
                        color = Ink.Brand,
                        fontSize = Txt.Tiny,
                        maxLines = 1,
                    )
                }
            }
        }

        Spacer(Modifier.height(Dim.Gap8))

        when {
            loading -> Box(
                Modifier.fillMaxWidth().padding(vertical = 40.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    color = Ink.Brand,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(24.dp),
                )
            }

            error.isNotBlank() -> Box(
                Modifier.fillMaxWidth().padding(vertical = 32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(error, color = Ink.Warn, fontSize = Txt.Caption)
                    Spacer(Modifier.height(Dim.Gap12))
                    PillButton("重试") {
                        scope.launch {
                            loading = true
                            error = ""
                            detail = runCatching { SourceRegistry.detail(drama) }.getOrNull()
                            loading = false
                            if (detail == null) error = "还是取不到，稍后再试"
                        }
                    }
                }
            }

            else -> {
                val episodes = detail?.episodes.orEmpty()
                LazyVerticalGrid(
                    columns = GridCells.Fixed(5),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(episodeGridHeight(episodes.size)),
                    contentPadding = PaddingValues(
                        start = Dim.PageH,
                        end = Dim.PageH,
                        bottom = Dim.BottomBar,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(Dim.Gap8),
                    verticalArrangement = Arrangement.spacedBy(Dim.Gap8),
                    userScrollEnabled = false,
                ) {
                    items(episodes, key = { it.targetId + "#" + it.index }) { ep ->
                        EpisodeCell(
                            episode = ep,
                            watched = resumeIndex == ep.index,
                            downloaded = ep.index in downloadedEpisodes,
                            onClick = { detail?.let { onPlay(shown, it, ep.index) } },
                            // 长按 = 单独下载这一集。
                            // 为什么不放按钮：格子只有 5 列、边长 44dp，塞不进第三个
                            // 图标；而且"下载某一集"是低频操作，长按正合适。
                            onLongClick = {
                                val n = queue.enqueue(shown, listOf(ep))
                                if (n > 0) {
                                    downloading = true
                                    Diag.info("Detail", "单集下载入队：第 ${ep.index} 集")
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 选集网格高度。
 *
 * `LazyVerticalGrid` 在 `verticalScroll` 的父容器里**必须有确定高度**
 * （否则会抛 "Vertically scrollable component was measured with an
 * infinity maximum height constraints"）。所以按行数算出来。
 */
private fun episodeGridHeight(count: Int): androidx.compose.ui.unit.Dp {
    val rows = (count + 4) / 5
    val cell = 44
    return (rows * cell + (rows - 1) * 8 + 8).dp
}

/** 一个选集格子。点=播放，长按=单独下载这一集。 */
@Composable
private fun EpisodeCell(
    episode: Episode,
    watched: Boolean,
    downloaded: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val bg = when {
        watched -> Ink.Brand
        episode.locked -> Ink.Card
        else -> Ink.CardHi
    }
    Box(
        Modifier
            .height(44.dp)
            .clip(RoundedCornerShape(Dim.RadiusSm))
            .background(bg)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = episode.index.toString(),
                color = if (watched) Color.White else Ink.Text,
                fontSize = Txt.Body,
                fontWeight = if (watched) FontWeight.Bold else FontWeight.Normal,
            )
            // 优先级：已下载 > 锁定。已下载是"能离线看"的正面信息，
            // 比"可能需要会员"更该让用户看到
            when {
                downloaded -> Icon(
                    Icons.Rounded.DownloadDone,
                    contentDescription = "已下载",
                    tint = Ink.Brand,
                    modifier = Modifier.size(9.dp),
                )

                episode.locked -> Icon(
                    Icons.Rounded.Lock,
                    contentDescription = "可能需要会员",
                    tint = Ink.TextFaint,
                    modifier = Modifier.size(9.dp),
                )
            }
        }
    }
}
