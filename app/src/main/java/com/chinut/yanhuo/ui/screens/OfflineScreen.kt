package com.chinut.yanhuo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chinut.yanhuo.data.DownloadQueue
import com.chinut.yanhuo.data.DownloadStore
import com.chinut.yanhuo.data.Drama
import com.chinut.yanhuo.data.OfflineDownloader
import com.chinut.yanhuo.ui.components.CoverImage
import com.chinut.yanhuo.ui.components.EmptyState
import com.chinut.yanhuo.ui.components.GradientTitle
import com.chinut.yanhuo.ui.components.PillButton
import com.chinut.yanhuo.ui.theme.Dim
import com.chinut.yanhuo.ui.theme.Ink
import com.chinut.yanhuo.ui.theme.Txt
import kotlinx.coroutines.launch
import java.io.File

/**
 * 离线页 —— 已下载的内容 + 正在下载的进度。
 *
 * # 两个区块的分工
 *
 *   1. **下载中**：来自 [DownloadQueue]（内存态），有进度条。
 *      点"暂停"只是停掉**排队中**的，正在下的那一集会下完 —— 半截的分片
 *      留着也没用，下完它体验更好、也不浪费已经花掉的流量。
 *   2. **已下载**：来自 [DownloadStore]（落盘记录），按剧分组。
 *      点一下直接播本地文件 —— **不需要联网**，这是这个页面的全部意义。
 *
 * # 为什么按剧分组而不是平铺
 *
 * 一部短剧动辄几十上百集。平铺的话列表会长到没法看，而且用户是"想找那部剧"
 * 而不是"想找某一集"。所以先列剧，剧下面挂集号。
 */
@Composable
fun OfflineScreen(
    queue: DownloadQueue,
    onPlay: (Drama, Int) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var groups by remember { mutableStateOf<List<Pair<String, List<DownloadStore.Row>>>>(emptyList()) }
    var totalBytes by remember { mutableStateOf(0L) }

    val queueState by queue.queue.collectAsState()
    val current by queue.current.collectAsState()
    val finished by queue.finishedCount.collectAsState()

    // 拉一次列表；每下完一集（finished 变化）再拉一次
    LaunchedEffect(finished) {
        DownloadStore.ensureLoaded()
        groups = DownloadStore.byDrama()
        totalBytes = DownloadStore.totalBytes()
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Dim.PageH,
            end = Dim.PageH,
            bottom = Dim.BottomBar + Dim.Gap24,
        ),
        verticalArrangement = Arrangement.spacedBy(Dim.Gap12),
    ) {
        // ==================== 头部 ====================
        item {
            Column(Modifier.padding(top = Dim.Gap12)) {
                GradientTitle("离线")
                Spacer(Modifier.height(2.dp))
                Text(
                    text = if (totalBytes > 0) {
                        "已下载 ${fmtSize(totalBytes)} · 断网也能看"
                    } else {
                        "下载后断网也能看"
                    },
                    color = Ink.TextFaint,
                    fontSize = Txt.Tiny,
                )
            }
        }

        // ==================== 下载中 ====================
        if (current != null || queueState.isNotEmpty()) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "下载中",
                        color = Ink.Text,
                        fontSize = Txt.Subtitle,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.width(Dim.Gap8))
                    Text(
                        "剩余 ${queueState.size} 集排队",
                        color = Ink.TextFaint,
                        fontSize = Txt.Tiny,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "暂停",
                        color = Ink.Brand,
                        fontSize = Txt.Caption,
                        modifier = Modifier.clickable { queue.stop() },
                    )
                }
            }
            current?.let { t ->
                item {
                    DownloadingCard(
                        title = t.drama.title,
                        label = "第 ${t.episode.index} 集",
                        progress = t.progress,
                        done = t.done,
                        total = t.total,
                    )
                }
            }
        }

        // ==================== 已下载 ====================
        if (groups.isEmpty() && current == null && queueState.isEmpty()) {
            item {
                EmptyState(
                    text = "还没有下载内容",
                    hint = "在剧集详情页点「下载整部」，或长按某一集单独下载。下载完就能断网观看。",
                )
            }
        }

        if (groups.isNotEmpty()) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "已下载",
                        color = Ink.Text,
                        fontSize = Txt.Subtitle,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.width(Dim.Gap8))
                    Text("${groups.size} 部", color = Ink.TextFaint, fontSize = Txt.Tiny)
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "全部删除",
                        color = Ink.TextDim,
                        fontSize = Txt.Caption,
                        modifier = Modifier.clickable {
                            scope.launch {
                                DownloadStore.clearAll()
                                groups = DownloadStore.byDrama()
                                totalBytes = DownloadStore.totalBytes()
                            }
                        },
                    )
                }
            }

            items(groups, key = { it.first }) { (key, rows) ->
                OfflineDramaCard(
                    key = key,
                    rows = rows,
                    onPlay = { epIndex ->
                        val d = dramaOf(rows.first())
                        onPlay(d, epIndex)
                    },
                    onDelete = {
                        scope.launch {
                            DownloadStore.removeDrama(key)
                            groups = DownloadStore.byDrama()
                            totalBytes = DownloadStore.totalBytes()
                        }
                    },
                )
            }
        }
    }
}

/** 正在下载的一张卡：标题 + 进度条 + 片数。 */
@Composable
private fun DownloadingCard(
    title: String,
    label: String,
    progress: Float,
    done: Int,
    total: Int,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dim.RadiusLg))
            .background(Ink.Card)
            .padding(Dim.Gap12),
    ) {
        Text(
            text = title,
            color = Ink.Text,
            fontSize = Txt.Body,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Ink.Brand, fontSize = Txt.Tiny)
            Spacer(Modifier.weight(1f))
            Text(
                text = if (total > 0) "$done / $total 片" else "准备中…",
                color = Ink.TextFaint,
                fontSize = Txt.Tiny,
            )
        }
        Spacer(Modifier.height(6.dp))
        if (total > 0) {
            LinearProgressIndicator(
                progress = { progress },
                color = Ink.Brand,
                trackColor = Ink.CardHi,
                modifier = Modifier.fillMaxWidth().height(3.dp),
            )
        }
    }
}

/** 一部已下载的剧：封面 + 集号网格（点即播）+ 删除。 */
@Composable
private fun OfflineDramaCard(
    key: String,
    rows: List<DownloadStore.Row>,
    onPlay: (Int) -> Unit,
    onDelete: () -> Unit,
) {
    val first = rows.first()
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dim.RadiusLg))
            .background(Ink.Card)
            .padding(Dim.Gap12),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .width(58.dp)
                    .height(78.dp)
                    .clip(RoundedCornerShape(Dim.RadiusSm)),
            ) {
                CoverImage(url = first.cover, title = first.title)
            }
            Spacer(Modifier.width(Dim.Gap12))
            Column(Modifier.weight(1f)) {
                Text(
                    text = first.title.ifBlank { key },
                    color = Ink.Text,
                    fontSize = Txt.Body,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "已下载 ${rows.size} 集 · ${fmtSize(rows.sumOf { it.bytes })}",
                    color = Ink.TextFaint,
                    fontSize = Txt.Tiny,
                )
                Spacer(Modifier.height(6.dp))
                // 点第一集就开始播，之后自动联播会顺着往下走 —— 离线也支持联播，
                // 因为详情分集是从本地记录重建的
                PillButton("播放", icon = Icons.Rounded.PlayArrow, filled = true) {
                    onPlay(rows.first().episodeIndex)
                }
            }
            Spacer(Modifier.width(Dim.Gap8))
            Icon(
                imageVector = Icons.Rounded.DeleteOutline,
                contentDescription = "删除下载",
                tint = Ink.TextFaint,
                modifier = Modifier
                    .width(24.dp)
                    .height(24.dp)
                    .clickable(onClick = onDelete),
            )
        }

        Spacer(Modifier.height(Dim.Gap8))
        // 已下载的集号（一行铺开；集数多时自动换行）
        Text(
            text = rows.joinToString("  ") { it.episodeIndex.toString() },
            color = Ink.TextDim,
            fontSize = Txt.Tiny,
            lineHeight = 18.sp,
        )
    }
}

/** 从离线记录还原 [Drama]（播放只需要 sourceId/targetId + 展示字段）。 */
private fun dramaOf(r: DownloadStore.Row) = Drama(
    sourceId = r.key.substringBefore(':'),
    targetId = r.key.substringAfter(':'),
    title = r.title,
    cover = r.cover,
    episodeCount = 0,
)

/** 人话体积。 */
private fun fmtSize(b: Long): String = when {
    b >= 1024L * 1024 * 1024 -> "%.1f GB".format(b / 1024.0 / 1024 / 1024)
    b >= 1024L * 1024 -> "%.1f MB".format(b / 1024.0 / 1024)
    b >= 1024 -> "%.0f KB".format(b / 1024.0)
    else -> "$b B"
}
