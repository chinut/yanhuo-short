package com.chinut.yanhuo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.NotificationsNone
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chinut.yanhuo.data.Drama
import com.chinut.yanhuo.data.SourceRegistry
import com.chinut.yanhuo.data.UserStore
import com.chinut.yanhuo.ui.components.CoverImage
import com.chinut.yanhuo.ui.components.EmptyState
import com.chinut.yanhuo.ui.components.GradientTitle
import com.chinut.yanhuo.ui.theme.Dim
import com.chinut.yanhuo.ui.theme.Ink
import com.chinut.yanhuo.ui.theme.Txt
import kotlinx.coroutines.launch

/**
 * 收藏页。
 *
 * 数据全部来自 [UserStore] 的本地记录 —— **不联网**，所以打开就是满的，
 * 不会转圈（封面走 Coil 的磁盘缓存）。
 */
@Composable
fun FavoritesScreen(onOpenDrama: (Drama) -> Unit) {
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf(UserStore.favorites()) }

    LaunchedEffect(Unit) {
        UserStore.ensureLoaded()
        rows = UserStore.favorites()
    }
    // 任何一次增删都会改变 rows 流，这里跟着刷新
    LaunchedEffect(UserStore.rows.value) { rows = UserStore.favorites() }

    Column(Modifier.fillMaxSize()) {
        ListHeader(
            title = "我的收藏",
            subtitle = if (rows.isEmpty()) "" else "${rows.size} 部",
        )

        if (rows.isEmpty()) {
            EmptyState(
                text = "还没有收藏任何短剧",
                hint = "在播放页点右上角的心形，或在详情页点「收藏」",
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(
                    start = Dim.PageH,
                    end = Dim.PageH,
                    bottom = Dim.BottomBar,
                ),
                verticalArrangement = Arrangement.spacedBy(Dim.Gap12),
            ) {
                items(rows, key = { it.key }) { r ->
                    UserRow(
                        title = r.title,
                        cover = r.cover,
                        badge = if (r.knownEpisodes > 0) "全 ${r.knownEpisodes} 集" else "",
                        actionIcon = Icons.Rounded.DeleteOutline,
                        actionDesc = "取消收藏",
                        onAction = {
                            scope.launch {
                                UserStore.removeFavorite(r.key)
                                rows = UserStore.favorites()
                            }
                        },
                        onClick = { onOpenDrama(dramaOf(r)) },
                    )
                }
            }
        }
    }
}

/**
 * 订阅页（连载订阅 + 更新提示）。
 *
 * 有更新的条目排在最前面并带紫色"有新集"角标 —— 这是需求里
 * "连载更新提示"的**应用内**部分（系统通知见 work/UpdateWorker）。
 */
@Composable
fun SubscribesScreen(onOpenDrama: (Drama) -> Unit) {
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf(UserStore.subscribes()) }

    LaunchedEffect(Unit) {
        UserStore.ensureLoaded()
        rows = UserStore.subscribes()
    }
    LaunchedEffect(UserStore.rows.value) {
        // 有更新的排前面，让用户一眼看到"哪几部该追了"
        rows = UserStore.subscribes().sortedByDescending { it.hasUpdate }
    }

    val updated = rows.count { it.hasUpdate }

    Column(Modifier.fillMaxSize()) {
        ListHeader(
            title = "连载订阅",
            subtitle = when {
                rows.isEmpty() -> ""
                updated > 0 -> "$updated 部有新集"
                else -> "${rows.size} 部 · 暂无更新"
            },
            subtitleColor = if (updated > 0) Ink.Magenta else Ink.TextFaint,
        )

        if (rows.isEmpty()) {
            EmptyState(
                text = "还没有订阅任何短剧",
                hint = "在详情页点「订阅」，有新集时会通知你",
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(
                    start = Dim.PageH,
                    end = Dim.PageH,
                    bottom = Dim.BottomBar,
                ),
                verticalArrangement = Arrangement.spacedBy(Dim.Gap12),
            ) {
                items(rows, key = { it.key }) { r ->
                    UserRow(
                        title = r.title,
                        cover = r.cover,
                        badge = when {
                            r.hasUpdate -> "有新集 · 已更新到 ${r.checkedEpisodes} 集"
                            r.knownEpisodes > 0 -> "全 ${r.knownEpisodes} 集"
                            else -> ""
                        },
                        badgeHighlight = r.hasUpdate,
                        actionIcon = Icons.Rounded.DeleteOutline,
                        actionDesc = "取消订阅",
                        onAction = {
                            scope.launch {
                                UserStore.removeSubscribe(r.key)
                                rows = UserStore.subscribes()
                            }
                        },
                        onClick = {
                            // 点开就算"看过更新了"，清掉提示
                            scope.launch { UserStore.clearUpdate(r.key) }
                            onOpenDrama(dramaOf(r))
                        },
                    )
                }
            }
        }
    }
}

/** 从本地记录还原成 [Drama]（只需要 key 中的源与 id，加上标题/封面用于显示）。 */
private fun dramaOf(r: UserStore.Row): Drama = Drama(
    sourceId = r.key.substringBefore(':'),
    targetId = r.key.substringAfter(':'),
    title = r.title,
    cover = r.cover,
    episodeCount = r.knownEpisodes,
)

/** 列表页顶栏。 */
@Composable
private fun ListHeader(title: String, subtitle: String, subtitleColor: Color = Ink.TextFaint) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Dim.PageH, vertical = Dim.Gap12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GradientTitle(title)
        Spacer(Modifier.width(Dim.Gap8))
        if (subtitle.isNotBlank()) {
            Text(subtitle, color = subtitleColor, fontSize = Txt.Caption)
        }
    }
}

/**
 * 一行：封面 + 标题 + 副标题 + 右侧动作。
 *
 * 用横排（而不是网格）的原因：收藏/订阅是**管理型**列表，
 * 用户要看清标题和一集数、以及能一键删掉；网格卡片塞不下这些。
 */
@Composable
private fun UserRow(
    title: String,
    cover: String,
    badge: String,
    badgeHighlight: Boolean = false,
    actionIcon: ImageVector,
    actionDesc: String,
    onAction: () -> Unit,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dim.Radius))
            .background(Ink.Card)
            .clickable(onClick = onClick)
            .padding(Dim.Gap8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(64.dp)
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(Dim.RadiusSm))
                .background(Ink.CardHi),
        ) {
            CoverImage(url = cover, title = title, modifier = Modifier.fillMaxSize())
            // 播放小标，暗示"点进去能看"
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(3.dp)
                    .size(18.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(Ink.Brand),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(13.dp),
                )
            }
        }

        Spacer(Modifier.width(Dim.Gap12))

        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                color = Ink.Text,
                fontSize = Txt.Body,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(Dim.Gap4))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (badge.isNotBlank()) {
                    Text(
                        text = badge,
                        color = if (badgeHighlight) Color.White else Ink.TextDim,
                        fontSize = Txt.Tiny,
                        fontWeight = if (badgeHighlight) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier
                            .clip(RoundedCornerShape(Dim.RadiusSm))
                            .background(if (badgeHighlight) Ink.Magenta else Ink.CardHi)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                    Spacer(Modifier.width(Dim.Gap6))
                }
                // 这里原来显示源名。App 只有一个内容源，标出来没有信息量，去掉。
            }
        }

        Spacer(Modifier.width(Dim.Gap8))

        Box(
            Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(Dim.RadiusSm))
                .clickable(onClick = onAction),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                actionIcon,
                contentDescription = actionDesc,
                tint = Ink.TextFaint,
                modifier = Modifier.size(19.dp),
            )
        }
    }
}

/** 收藏/订阅都为空时的通用图标（保留给将来的空态插图用）。 */
@Suppress("unused")
private val emptyIcons = listOf(Icons.Rounded.FavoriteBorder, Icons.Rounded.NotificationsNone)
