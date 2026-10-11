package com.chinut.yanhuo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chinut.yanhuo.data.Drama
import com.chinut.yanhuo.ui.theme.Dim
import com.chinut.yanhuo.ui.theme.Ink
import com.chinut.yanhuo.ui.theme.Txt

/**
 * 瀑布流卡片。
 *
 * # 集数角标是需求明确要求的
 *
 * 「主页视频展示界面要注明集数」—— 所以集数不是可有可无的装饰，
 * 而是**每张卡片的固定组成部分**：
 *
 * ```
 * ┌──────────────────┐
 * │ ① 名次（只在榜上）│
 * │                  │
 * │      封面        │
 * │                  │
 * │ [全 80 集]  ▶    │  ← 集数角标（左下，橙底白字）
 * └──────────────────┘
 *   首辅娇娘            ← 标题
 * ```
 *
 * # 为什么自己画角标而不是用 Badge
 *
 * Material3 的 `Badge` 尺寸跟随内容，在瀑布流里会导致不同卡片角标大小不一。
 * 这里需要**固定高度、固定字号**，横向对齐才整齐。
 *
 * @param rank 大于 0 时在左上角画名次（热剧榜用）。
 */
@Composable
fun DramaCard(
    drama: Drama,
    modifier: Modifier = Modifier,
    rank: Int = 0,
    /** 观看进度 0~1；大于 0 时在封面底部画一条进度线。 */
    progress: Float = 0f,
    onClick: () -> Unit,
) {
    // 卡片宽度决定字号：窄屏（两列）和宽屏（平板三列）都不该用同一个字号
    var cardWidth by remember { mutableIntStateOf(0) }
    val titleSize = when {
        cardWidth == 0 -> Txt.Body
        cardWidth < 150 -> 12.sp
        cardWidth < 210 -> 13.sp
        else -> Txt.Body
    }

    Column(modifier = modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                // 3:4 是短剧封面的实际比例（实测 720×960 左右）
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(Dim.Radius))
                .background(Ink.Card)
                .onSizeChanged { cardWidth = it.width }
                .clickable(onClick = onClick),
        ) {
            CoverImage(
                url = drama.cover,
                title = drama.title,
                modifier = Modifier.fillMaxSize(),
            )

            // 底部渐变：给集数角标和标题一个可读的底
            BottomScrim(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .align(Alignment.BottomCenter),
                strength = 0.85f,
            )

            // ---------- 集数角标（左下） ----------
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(Dim.Gap8),
            ) {
                EpisodeBadge(drama = drama, compact = cardWidth in 1..150)
            }

            // ---------- 名次角标（左上） ----------
            if (rank > 0) RankBadge(rank = rank, modifier = Modifier.align(Alignment.TopStart))

            // ---------- 热度（右上） ----------
            if (drama.hot.isNotBlank()) {
                Text(
                    text = drama.hot,
                    color = Ink.Text.copy(alpha = 0.92f),
                    fontSize = Txt.Tiny,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(Dim.Gap6)
                        .background(Ink.Deep.copy(alpha = 0.55f), RoundedCornerShape(Dim.RadiusSm))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                )
            }

            // ---------- 观看进度（封面最底部一条线） ----------
            if (progress > 0f) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(Color.White.copy(alpha = 0.18f)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(progress.coerceIn(0f, 1f))
                            .height(3.dp)
                            .background(Ink.Brand),
                    )
                }
            }
        }

        Spacer(Modifier.height(Dim.Gap6))

        Text(
            text = drama.title,
            color = Ink.Text,
            fontSize = titleSize,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = (titleSize.value + 4).sp,
        )

        // 标签行：有才画，避免卡片高度参差
        if (drama.tags.isNotEmpty()) {
            Spacer(Modifier.height(Dim.Gap2))
            Text(
                text = drama.tags.take(2).joinToString(" · "),
                color = Ink.TextFaint,
                fontSize = Txt.Tiny,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 集数角标。
 *
 * 这是需求里"注明集数"的落点。三种状态：
 *   · 已知集数  → `全 80 集`（橙底，最醒目）
 *   · 连载中    → `连载中`（灰底，因为确实不知道有多少集）
 *   · 加载中    → 不显示（避免闪一下又变）
 */
@Composable
private fun EpisodeBadge(drama: Drama, compact: Boolean) {
    val known = drama.episodeCount > 0
    val bg = if (known) Ink.Brand else Ink.Card.copy(alpha = 0.85f)
    val fg = if (known) Color.White else Ink.TextDim
    val text = if (known) "全 ${drama.episodeCount} 集" else "连载中"

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .background(bg, RoundedCornerShape(Dim.RadiusSm))
            .padding(horizontal = if (compact) 4.dp else 6.dp, vertical = 2.dp),
    ) {
        if (known) {
            Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(if (compact) 10.dp else 12.dp),
            )
        }
        Text(
            text = text,
            color = fg,
            fontSize = if (compact) 9.sp else Txt.Tiny,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

/**
 * 名次角标（热剧榜用）。
 *
 * 前三名用金色渐变，其余用深色 —— 让"最热的三部"在第一眼就能分辨出来，
 * 而不是 20 个一模一样的数字。
 */
@Composable
private fun RankBadge(rank: Int, modifier: Modifier = Modifier) {
    val top3 = rank <= 3
    val brush = if (top3) {
        Brush.linearGradient(listOf(Color(0xFFFFD25F), Color(0xFFF3970B)))
    } else {
        Brush.linearGradient(listOf(Ink.Card.copy(alpha = 0.9f), Ink.Card.copy(alpha = 0.9f)))
    }

    Box(
        modifier
            .padding(Dim.Gap6)
            .size(22.dp)
            .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 2.dp, bottomEnd = 8.dp, bottomStart = 2.dp))
            .background(brush),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = rank.toString(),
            color = if (top3) Color(0xFF3A2400) else Ink.Text,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * 横滑卡片（榜单 / 继续观看用）。
 *
 * 和 [DramaCard] 的区别：**固定宽度**，因为横滑列表里宽度参差会很难看。
 */
@Composable
fun DramaCardWide(
    drama: Drama,
    modifier: Modifier = Modifier,
    width: androidx.compose.ui.unit.Dp = 108.dp,
    rank: Int = 0,
    progress: Float = 0f,
    onClick: () -> Unit,
) {
    Column(modifier.width(width)) {
        Box(
            Modifier
                .width(width)
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(Dim.Radius))
                .background(Ink.Card)
                .clickable(onClick = onClick),
        ) {
            CoverImage(
                url = drama.cover,
                title = drama.title,
                modifier = Modifier.fillMaxSize(),
            )
            BottomScrim(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .align(Alignment.BottomCenter),
                strength = 0.8f,
            )
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp),
            ) {
                EpisodeBadge(drama = drama, compact = true)
            }
            if (rank > 0) {
                RankBadge(rank = rank, modifier = Modifier.align(Alignment.TopStart))
            }
            if (progress > 0f) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(Color.White.copy(alpha = 0.18f)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(progress.coerceIn(0f, 1f))
                            .height(3.dp)
                            .background(Ink.Brand),
                    )
                }
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(
            text = drama.title,
            color = Ink.Text,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
