package com.chinut.yanhuo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.chinut.yanhuo.ui.theme.Ink
import com.chinut.yanhuo.ui.theme.Txt

/**
 * 封面图。
 *
 * # 为什么失败了要画渐变而不是留空白
 *
 * 封面加载失败（网络慢、CDN 抽风）时留一块纯黑，界面看起来像"坏了"。
 * 用品牌色渐变 + 剧名首字兜底，用户至少知道这里是什么内容。
 *
 * （七猫的封面在 `cdn.wtzw.com`，实测直连可加载，不需要 Referer。）
 */
@Composable
fun CoverImage(
    url: String,
    title: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val context = LocalContext.current

    Box(modifier) {
        // 底层始终画一张渐变，图片没到位时就是它
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        listOf(Ink.CardHi, Ink.Card, Ink.Base),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (title.isNotBlank()) {
                Text(
                    text = title.take(1),
                    color = Ink.TextFaint,
                    fontSize = 30.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }

        if (url.startsWith("http")) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(url)
                    // 封面尺寸有限，按控件大小解码，省内存（列表里能同时挂几十张）
                    .crossfade(true)
                    .build(),
                contentDescription = title,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * 底部渐变遮罩。
 *
 * 封面上的白色文字（集数、标题）直接压在图上会看不清 ——
 * 海报右侧经常是浅色区域。压一层从透明到黑的渐变，文字就永远可读。
 */
@Composable
fun BottomScrim(
    modifier: Modifier = Modifier,
    strength: Float = 0.92f,
) {
    Box(
        modifier.fillMaxWidth().background(
            Brush.verticalGradient(
                0f to androidx.compose.ui.graphics.Color.Transparent,
                0.45f to Ink.Deep.copy(alpha = 0.35f * strength),
                1f to Ink.Deep.copy(alpha = strength),
            ),
        ),
    )
}

/** 单行省略标题（卡片用）。 */
@Composable
fun CardTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = Ink.Text,
        fontSize = Txt.Body,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}
