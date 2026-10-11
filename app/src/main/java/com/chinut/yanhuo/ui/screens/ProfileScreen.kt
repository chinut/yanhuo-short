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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import com.chinut.yanhuo.core.Prefs
import com.chinut.yanhuo.data.Drama
import com.chinut.yanhuo.data.DramaLibrary
import com.chinut.yanhuo.data.UserStore
import com.chinut.yanhuo.ui.components.CoverImage
import com.chinut.yanhuo.ui.components.GradientTitle
import com.chinut.yanhuo.ui.components.PillButton
import com.chinut.yanhuo.ui.theme.Dim
import com.chinut.yanhuo.ui.theme.Ink
import com.chinut.yanhuo.ui.theme.Txt
import kotlinx.coroutines.launch

/**
 * 「我的」页。
 *
 * 承担三件事，都是"没有更好的地方放"的：
 *
 *   1. **观看历史** —— 断点续播的入口之一（首页有"继续观看"，这里是全量）
 *   2. **数据源自检** —— 源挂了的时候，用户能自己看到"是 DNS 还是付费墙"，
 *      不用连电脑抓 logcat
 *   3. **播放 / 通知开关** —— 自动联播、断点续播、更新通知
 *
 * 这里**不再放**「数据源」和「诊断」两块（2026-10 移除）：
 *   · 数据源：产品只有一个源，用户既不能选也不需要知道，
 *     列出来只会让人以为"是不是少配了什么"；
 *   · 诊断：详细日志开关 + 应用内日志查看，是排障用的，
 *     对普通用户是纯噪声（要看日志可以 adb logcat，开发者自己够用）。
 *   两块的实现没删（源与日志仍在），只是不再占用户的界面。
 */
@Composable
fun ProfileScreen(onOpenDrama: (Drama) -> Unit) {
    val scope = rememberCoroutineScope()

    var history by remember { mutableStateOf(UserStore.history(60)) }
    var autoNext by remember { mutableStateOf(Prefs.autoNext) }
    var resume by remember { mutableStateOf(Prefs.resumeLastPosition) }
    var notify by remember { mutableStateOf(Prefs.notifyUpdates) }

    LaunchedEffect(Unit) {
        UserStore.ensureLoaded()
        history = UserStore.history(60)
    }
    LaunchedEffect(UserStore.rows.value) { history = UserStore.history(60) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Dim.PageH,
            end = Dim.PageH,
            bottom = Dim.BottomBar,
        ),
        verticalArrangement = Arrangement.spacedBy(Dim.Gap16),
    ) {
        // ==================== 头部 ====================
        item {
            Column(Modifier.padding(top = Dim.Gap12)) {
                GradientTitle("我的")
                Spacer(Modifier.height(Dim.Gap4))
                Text(
                    text = "焰火短视频 · 焰火随想 美好随现",
                    color = Ink.TextFaint,
                    fontSize = Txt.Tiny,
                )
            }
        }

        // ==================== 播放设置 ====================
        item {
            SectionCard(title = "播放", icon = Icons.Rounded.PlayArrow) {
                SwitchRow("自动联播下一集", "一集播完自动接上，短剧的核心体验", autoNext) {
                    autoNext = it
                    Prefs.autoNext = it
                }
                SwitchRow("断点续播", "再次打开时从上次的位置继续", resume) {
                    resume = it
                    Prefs.resumeLastPosition = it
                }
            }
        }

        // ==================== 通知 ====================
        item {
            SectionCard(title = "连载更新提示", icon = Icons.Rounded.Info) {
                SwitchRow("有新集时通知我", "订阅的短剧更新时发系统通知", notify) {
                    notify = it
                    Prefs.notifyUpdates = it
                }
                Spacer(Modifier.height(Dim.Gap4))
                Text(
                    text = "上次检查：" + formatTime(Prefs.lastUpdateCheck) +
                        " · 打开 App 时会自动检查一次",
                    color = Ink.TextFaint,
                    fontSize = Txt.Tiny,
                )
            }
        }

        // ==================== 观看历史 ====================
        item {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "观看历史",
                    color = Ink.Text,
                    fontSize = Txt.Subtitle,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(Dim.Gap8))
                Text("${history.size} 条", color = Ink.TextFaint, fontSize = Txt.Tiny)
                Spacer(Modifier.weight(1f))
                if (history.isNotEmpty()) {
                    Text(
                        text = "清空",
                        color = Ink.TextDim,
                        fontSize = Txt.Caption,
                        modifier = Modifier.clickable {
                            scope.launch {
                                UserStore.clearHistory()
                                history = UserStore.history(60)
                            }
                        },
                    )
                }
            }
        }

        if (history.isEmpty()) {
            item {
                Text(
                    "还没有观看记录。看过一集之后，这里会显示观看进度。",
                    color = Ink.TextFaint,
                    fontSize = Txt.Caption,
                )
            }
        } else {
            items(history, key = { it.key }) { r ->
                HistoryRow(
                    title = r.title,
                    cover = r.cover,
                    episodeName = r.episodeName.ifBlank { "第 ${r.episodeIndex} 集" },
                    progress = r.progress,
                    finished = r.finished,
                    timeLabel = formatTime(r.updatedAt),
                    onClick = {
                        onOpenDrama(
                            Drama(
                                sourceId = r.key.substringBefore(':'),
                                targetId = r.key.substringAfter(':'),
                                title = r.title,
                                cover = r.cover,
                                episodeCount = r.knownEpisodes,
                            ),
                        )
                    },
                    onRemove = {
                        scope.launch {
                            UserStore.removeProgress(r.key)
                            history = UserStore.history(60)
                        }
                    },
                )
            }
        }

        // ==================== 存储 ====================
        item {
            SectionCard(title = "存储", icon = Icons.Rounded.DeleteOutline) {
                val libBytes = DramaLibrary.diskBytes()
                val userBytes = UserStore.diskBytes()
                Text(
                    text = "片库缓存 ${fmtSize(libBytes)} · 我的数据 ${fmtSize(userBytes)}",
                    color = Ink.TextFaint,
                    fontSize = Txt.Tiny,
                )
                Spacer(Modifier.height(Dim.Gap8))
                Row(horizontalArrangement = Arrangement.spacedBy(Dim.Gap8)) {
                    PillButton("清空片库缓存", danger = true) {
                        DramaLibrary.clear()
                    }
                    PillButton("清空全部数据", danger = true) {
                        UserStore.clearAll()
                        DramaLibrary.clear()
                        history = emptyList()
                    }
                }
            }
        }

        // ==================== 免责 ====================
        item {
            Text(
                text = "本 App 只做播放器与界面聚合，不存储、不传播任何影视资源。" +
                    "内容与播放地址均来自第三方公开网页接口，仅供个人学习研究使用。",
                color = Ink.TextFaint,
                fontSize = Txt.Tiny,
                lineHeight = 15.sp,
                modifier = Modifier.padding(top = Dim.Gap8),
            )
        }
    }
}

/** 一张设置卡片。 */
@Composable
private fun SectionCard(
    title: String,
    icon: ImageVector,
    content: @Composable () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dim.RadiusLg))
            .background(Ink.Card)
            .padding(Dim.Gap12),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = Ink.Brand, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(Dim.Gap6))
            Text(
                text = title,
                color = Ink.Text,
                fontSize = Txt.Subtitle,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(Dim.Gap12))
        content()
    }
}

/** 一行开关。 */
@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = Dim.Gap4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Ink.Text, fontSize = Txt.Body)
            if (subtitle.isNotBlank()) {
                Text(subtitle, color = Ink.TextFaint, fontSize = Txt.Tiny, lineHeight = 15.sp)
            }
        }
        Spacer(Modifier.width(Dim.Gap8))
        YanhuoSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** 统一配色的开关（Material3 默认色和本 App 的橙紫不搭）。 */
@Composable
private fun YanhuoSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = Ink.Brand,
            uncheckedThumbColor = Ink.TextFaint,
            uncheckedTrackColor = Ink.CardHi,
            uncheckedBorderColor = Ink.Line,
        ),
    )
}

/** 一条历史记录。 */
@Composable
private fun HistoryRow(
    title: String,
    cover: String,
    episodeName: String,
    progress: Float,
    finished: Boolean,
    timeLabel: String,
    onClick: () -> Unit,
    onRemove: () -> Unit,
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
                .width(48.dp)
                .height(64.dp)
                .clip(RoundedCornerShape(Dim.RadiusSm))
                .background(Ink.CardHi),
        ) {
            CoverImage(url = cover, title = title, modifier = Modifier.fillMaxSize())
            // 进度条压在封面底部
            if (progress > 0f) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(Color.White.copy(alpha = 0.2f)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(progress.coerceIn(0f, 1f))
                            .height(3.dp)
                            .background(if (finished) Ink.TextFaint else Ink.Brand),
                    )
                }
            }
        }

        Spacer(Modifier.width(Dim.Gap12))

        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                color = Ink.Text,
                fontSize = Txt.Body,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "$episodeName · ${if (finished) "已看完" else "${(progress * 100).toInt()}%"}",
                color = if (finished) Ink.TextFaint else Ink.Accent,
                fontSize = Txt.Tiny,
            )
            Text(timeLabel, color = Ink.TextFaint, fontSize = Txt.Tiny)
        }

        Box(
            Modifier
                .size(34.dp)
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.DeleteOutline,
                contentDescription = "删除记录",
                tint = Ink.TextFaint,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** 时间戳 → `刚刚` / `N 分钟前` / `M-D HH:mm`。 */
private fun formatTime(ms: Long): String {
    if (ms <= 0L) return "从未"
    val diff = System.currentTimeMillis() - ms
    return when {
        diff < 60_000L -> "刚刚"
        diff < 3_600_000L -> "${diff / 60_000} 分钟前"
        diff < 86_400_000L -> "${diff / 3_600_000} 小时前"
        diff < 7 * 86_400_000L -> "${diff / 86_400_000} 天前"
        else -> java.text.SimpleDateFormat("M-d HH:mm", java.util.Locale.CHINA)
            .format(java.util.Date(ms))
    }
}

private fun fmtSize(bytes: Long): String = when {
    bytes <= 0L -> "0 B"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
}
