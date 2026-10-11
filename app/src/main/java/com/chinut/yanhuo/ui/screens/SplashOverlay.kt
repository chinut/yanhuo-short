package com.chinut.yanhuo.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.chinut.yanhuo.R
import com.chinut.yanhuo.ui.components.GradientTitle
import com.chinut.yanhuo.ui.theme.Ink
import com.chinut.yanhuo.ui.theme.Txt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 品牌语用的暖白（比正文略暖，和橙色 Logo 呼应）。 */
private val WarmWhite = Color(0xFFF3E2D2)

/**
 * 开屏覆盖层。
 *
 * # 为什么窗口背景里已经有开屏图，这里还要再画一层
 *
 * 两者分工不同：
 *
 * | 层 | 作用 |
 * |---|---|
 * | `windowBackground`（`splash_window.xml`） | 覆盖**点图标到第一帧**之间的空白。系统在进程起来之前就画它，所以用户看不到黑屏 |
 * | 这个覆盖层 | 第一帧之后做**动画**：Logo 放大淡入、品牌语浮现、整层淡出接进首页 |
 *
 * 只有窗口背景，进首页是"啪"一下切过去，很生硬；
 * 只有这个覆盖层，冷启动那半秒会先黑一下。
 *
 * # 时长为什么是 1.6 秒
 *
 * 首页数据是**同时在后台拉的**（根组件里 `HomeState.load` 已经跑起来了），
 * 所以这段时间不是纯粹浪费。再长就变成"为了动画而动画"了。
 */
@Composable
fun SplashOverlay() {
    // 只在本次启动放一次。状态活在根 Composable 里，重组不会重置。
    var visible by remember { mutableStateOf(true) }

    val logoScale = remember { Animatable(0.82f) }
    val logoAlpha = remember { Animatable(0f) }
    val textAlpha = remember { Animatable(0f) }
    val wholeAlpha = remember { Animatable(1f) }

    LaunchedEffect(Unit) {
        // 缩放和淡入并行跑，视觉上是"边放大边浮现"
        launch { logoScale.animateTo(1f, tween(620, easing = FastOutSlowInEasing)) }
        logoAlpha.animateTo(1f, tween(420, easing = FastOutSlowInEasing))
        textAlpha.animateTo(1f, tween(360))
        // 停一下让人看清品牌语
        delay(500)
        wholeAlpha.animateTo(0f, tween(300))
        visible = false
    }

    if (!visible) return

    Box(
        Modifier
            .fillMaxSize()
            .alpha(wholeAlpha.value)
            .background(Brush.verticalGradient(listOf(Ink.Deep, Ink.Base, Ink.Deep))),
        contentAlignment = Alignment.Center,
    ) {
        // 背景铺整张开屏图（自带 slogan），压暗一些避免和前景文字打架
        Image(
            painter = painterResource(R.drawable.splash_brand),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().alpha(0.42f),
        )

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // 圆形 Logo 单独出一张图，便于做缩放动画
            Image(
                painter = painterResource(R.drawable.logo_yanhuo),
                contentDescription = "焰火短视频",
                modifier = Modifier
                    .size(132.dp)
                    .scale(logoScale.value)
                    .alpha(logoAlpha.value),
            )

            Spacer(Modifier.height(22.dp))

            GradientTitle("焰火短视频", modifier = Modifier.alpha(textAlpha.value))

            Spacer(Modifier.height(10.dp))

            Text(
                text = "焰火随想  美好随现",
                color = WarmWhite,
                fontSize = Txt.Body,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.alpha(textAlpha.value),
            )
        }

        // 底部提示：让"等待"有解释，而不是看起来卡住了
        Text(
            text = "正在准备内容…",
            color = Ink.TextFaint,
            fontSize = Txt.Tiny,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 64.dp)
                .alpha(textAlpha.value * 0.9f),
        )
    }
}
