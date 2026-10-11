package com.chinut.yanhuo.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chinut.yanhuo.data.Drama
import com.chinut.yanhuo.data.SourceRegistry
import com.chinut.yanhuo.data.UserStore
import com.chinut.yanhuo.ui.components.DramaCard
import com.chinut.yanhuo.ui.components.DramaCardWide
import com.chinut.yanhuo.ui.components.GradientTitle
import com.chinut.yanhuo.ui.components.PillButton
import com.chinut.yanhuo.ui.components.RoundIconButton
import com.chinut.yanhuo.ui.theme.Dim
import com.chinut.yanhuo.ui.theme.Ink
import com.chinut.yanhuo.ui.theme.Txt
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * 首页。
 *
 * 结构（一个 `LazyVerticalGrid`，两列）：
 *
 * ```
 * ┌────────────────────────────────────┐
 * │ 焰火短视频            [🔍 搜索]      │  ← 顶栏（吸顶）
 * ├────────────────────────────────────┤
 * │ 🔥 热剧榜                    [更多] │  ← 第 1 行：整行
 * │  ①  ②  ③  ④ →                      │  ← 热剧横滑（带名次）
 * ├────────────────────────────────────┤
 * │ 继续观看                            │  ← 有进度时才出现
 * │  [卡片][卡片][卡片] →               │
 * ├────────────────────────────────────┤
 * │ 推荐                                │
 * │ ┌──────┐ ┌──────┐                  │  ← 两列瀑布流
 * │ │全80集│ │全69集│                  │
 * │ └──────┘ └──────┘                  │
 * │ ┌──────┐ ┌──────┐                  │
 * │ └──────┘ └──────┘                  │
 * │        加载中…                      │
 * └────────────────────────────────────┘
 * ```
 *
 * # 为什么用 Grid 而不是 Masonry
 *
 * 短剧封面比例统一（3:4），用 `LazyVerticalGrid` 行高天然对齐；
 * `LazyVerticalStaggeredGrid` 反而会因为标题行数不同而参差。
 * 卡片短一边高对不齐 = 视觉噪音。
 */
@Composable
fun HomeScreen(
    state: HomeState,
    onOpenSearch: () -> Unit,
    onOpenDrama: (Drama) -> Unit,
    onOpenTag: (String) -> Unit,
    onOpenSubscribes: () -> Unit,
) {
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()

    // 本地数据（收藏 / 进度）每次进首页刷新一次，保证从收藏页回来红点是对的
    var progressMap by remember { mutableStateOf<Map<String, Float>>(emptyMap()) }
    var updateCount by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        UserStore.ensureLoaded()
        progressMap = UserStore.rows.value
            .filter { it.progress > 0f && !it.finished }
            .associate { it.key to it.progress }
        updateCount = UserStore.updateCount()
        // 进首页就同步一次集数（详情页可能补全过）
        state.syncEpisodeCounts()
    }

    // 数据到齐后重新算一次进度（避免首帧为空的空窗）
    LaunchedEffect(state.feed.size) {
        progressMap = UserStore.rows.value
            .filter { it.progress > 0f && !it.finished }
            .associate { it.key to it.progress }
        updateCount = UserStore.updateCount()
    }

    // ---------- 滚到底自动翻页 ----------
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collect { last ->
                val total = gridState.layoutInfo.totalItemsCount
                // 提前 4 项开始加载，滚动到底时不会有明显停顿
                if (total > 0 && last >= total - 4) {
                    state.load(reset = false)
                }
            }
    }

    val continueRows = UserStore.continueWatching(limit = 12)
    val rowsByKey = UserStore.rows.value.associateBy { it.key }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Dim.PageH,
            end = Dim.PageH,
            bottom = Dim.BottomBar + Dim.Gap24,
        ),
        horizontalArrangement = Arrangement.spacedBy(Dim.Gap12),
        verticalArrangement = Arrangement.spacedBy(Dim.Gap16),
    ) {
        // ==================== 顶部品牌 + 搜索入口 ====================
        item(span = { GridItemSpan(maxLineSpan) }, key = "topbar") {
            TopBar(
                totalShown = state.rank.size + state.feed.size,
                updateCount = updateCount,
                onOpenSearch = onOpenSearch,
                onOpenSubscribes = onOpenSubscribes,
            )
        }

        // ==================== 热剧榜 ====================
        if (state.rank.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "rank-title") {
                SectionHeader(
                    icon = Icons.Rounded.LocalFireDepartment,
                    title = "热剧榜",
                    // 中性说法：只说明怎么排的。不写源名 ——
                    // 一是 App 只有一个源、用户不需要知道供应方，
                    // 二是写死源名在换源后会变成错误信息
                    subtitle = "热度排序",
                )
            }
            item(span = { GridItemSpan(maxLineSpan) }, key = "rank-row") {
                LazyRow(
                    state = rememberLazyListState(),
                    horizontalArrangement = Arrangement.spacedBy(Dim.Gap8),
                ) {
                    items(state.rank, key = { it.key }) { d ->
                        DramaCardWide(
                            drama = d,
                            rank = d.rank,
                            width = 104.dp,
                            progress = progressMap[d.key] ?: 0f,
                            onClick = { onOpenDrama(d) },
                        )
                    }
                }
            }
        }

        // ==================== 继续观看（断点续播入口） ====================
        if (continueRows.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "continue-title") {
                SectionHeader(title = "继续观看", subtitle = "接着上次的进度")
            }
            item(span = { GridItemSpan(maxLineSpan) }, key = "continue-row") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(Dim.Gap8)) {
                    items(continueRows, key = { it.key }) { r ->
                        val d = Drama(
                            sourceId = r.key.substringBefore(':'),
                            targetId = r.key.substringAfter(':'),
                            title = r.title,
                            cover = r.cover,
                            episodeCount = r.knownEpisodes,
                        )
                        Box {
                            DramaCardWide(
                                drama = d,
                                width = 104.dp,
                                progress = r.progress,
                                onClick = { onOpenDrama(d) },
                            )
                            // 看到第几集 压在封面上
                            Text(
                                text = r.episodeName.ifBlank { "第 ${r.episodeIndex} 集" },
                                color = Ink.Text,
                                fontSize = 10.sp,
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(5.dp)
                                    .background(
                                        Ink.Deep.copy(alpha = 0.7f),
                                        RoundedCornerShape(Dim.RadiusSm),
                                    )
                                    .padding(horizontal = 5.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            }
        }

        // ==================== 推荐流标题 ====================
        item(span = { GridItemSpan(maxLineSpan) }, key = "feed-title") {
            SectionHeader(
                title = "推荐",
                subtitle = when {
                    state.loadingMore -> "加载中…"
                    state.feedEnded -> "已经到底了"
                    else -> "为你挑选的短剧"
                },
            )
        }

        // ==================== 瀑布流 ====================
        items(state.feed, key = { it.key }) { d ->
            DramaCard(
                drama = d,
                progress = progressMap[d.key] ?: 0f,
                onClick = { onOpenDrama(d) },
            )
        }

        // ==================== 状态行 ====================
        item(span = { GridItemSpan(maxLineSpan) }, key = "status") {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = Dim.Gap16),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    state.loading && state.feed.isEmpty() && state.rank.isEmpty() -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(
                                color = Ink.Brand,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(26.dp),
                            )
                            Spacer(Modifier.height(Dim.Gap8))
                            Text("正在取内容…", color = Ink.TextFaint, fontSize = Txt.Caption)
                        }
                    }

                    state.error.isNotBlank() -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(state.error, color = Ink.Warn, fontSize = Txt.Caption)
                            Spacer(Modifier.height(Dim.Gap8))
                            PillButton("重新加载") { state.load(reset = true) }
                        }
                    }

                    state.loadingMore -> {
                        CircularProgressIndicator(
                            color = Ink.TextFaint,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(20.dp),
                        )
                    }

                    else -> Text(
                        text = if (state.feedEnded) "— 到底了 —" else "",
                        color = Ink.TextFaint,
                        fontSize = Txt.Tiny,
                    )
                }
            }
        }
    }
}

/**
 * 顶栏。
 *
 * 搜索按钮放在这里（而不是单独做成底部 Tab）：短剧用户的动作顺序是
 * "看到一部 → 想找类似的"，搜索是**首页的延伸**，不是并列的一级入口。
 */
@Composable
private fun TopBar(
    totalShown: Int,
    updateCount: Int,
    onOpenSearch: () -> Unit,
    onOpenSubscribes: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = Dim.Gap12, bottom = Dim.Gap4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            GradientTitle("焰火短视频")
            Spacer(Modifier.height(2.dp))
            Text(
                // 不显示源名：App 只有一个源，用户不需要知道内容供应方。
                // 只报"已加载多少部"，有更新时追加更新数。
                text = "已加载 $totalShown 部" +
                    if (updateCount > 0) " · $updateCount 部有更新" else "",
                color = Ink.TextFaint,
                fontSize = Txt.Tiny,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        RoundIconButton(icon = Icons.Rounded.Search, desc = "搜索", onClick = onOpenSearch)
    }
}

/** 小节标题。 */
@Composable
private fun SectionHeader(
    title: String,
    subtitle: String = "",
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = Dim.Gap8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = Ink.Brand, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Dim.Gap6))
        }
        Text(
            text = title,
            color = Ink.Text,
            fontSize = Txt.Subtitle,
            fontWeight = FontWeight.SemiBold,
        )
        if (subtitle.isNotBlank()) {
            Spacer(Modifier.width(Dim.Gap8))
            Text(
                text = subtitle,
                color = Ink.TextFaint,
                fontSize = Txt.Tiny,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
