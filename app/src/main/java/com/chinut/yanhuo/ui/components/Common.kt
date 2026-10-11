package com.chinut.yanhuo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chinut.yanhuo.ui.theme.Dim
import com.chinut.yanhuo.ui.theme.Ink
import com.chinut.yanhuo.ui.theme.Txt

/**
 * 品牌标题。
 *
 * 用渐变文字而不是纯色，是为了让"焰火"这两个字本身带上图标里的橙→金过渡，
 * 顶部一眼就能和图标呼应上。实现方式是把 `Brush` 交给 `TextStyle.brush`
 * （Compose 1.4+ 的能力），比自己切图简单得多，也不会糊。
 */
@Composable
fun GradientTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        style = TextStyle(
            brush = Brush.horizontalGradient(listOf(Ink.BrandGlow, Ink.Brand, Ink.Magenta)),
            fontSize = Txt.Title,
            fontWeight = FontWeight.Bold,
        ),
    )
}

/** 圆形图标按钮（顶栏搜索用）。 */
@Composable
fun RoundIconButton(
    icon: ImageVector,
    desc: String,
    onClick: () -> Unit,
    tint: Color = Ink.Text,
) {
    Box(
        Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(Ink.Card)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = desc, tint = tint, modifier = Modifier.size(19.dp))
    }
}

/**
 * 药丸按钮。
 *
 * ⚠️ 参数顺序是刻意的：`onClick` **必须放最后一个**，这样所有调用点都能写成
 * `PillButton("重试") { ... }` 的尾随 lambda 形式。
 * （写在中间时，命名参数之后的尾随 lambda 会被 Kotlin 拒绝，
 * 报 "No value passed for parameter 'onClick'"，非常费解。）
 *
 * 三种样式：
 *  · [filled] 主操作（橙色实底）
 *  · 默认     次要操作（卡片底）
 *  · [danger] 破坏性操作（警告色文字）
 */
@Composable
fun PillButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    filled: Boolean = false,
    danger: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val bg = when {
        !enabled -> Ink.Card.copy(alpha = 0.5f)
        filled -> Ink.Brand
        else -> Ink.Card
    }
    val fg = when {
        !enabled -> Ink.TextFaint
        filled -> Color.White
        danger -> Ink.Warn
        else -> Ink.Text
    }

    Row(
        modifier
            .height(38.dp)
            .clip(RoundedCornerShape(Dim.RadiusXl))
            .background(bg)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = Dim.Gap16),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(Dim.Gap6))
        }
        Text(text = text, color = fg, fontSize = Txt.Body, fontWeight = FontWeight.Medium)
    }
}

/** 小标签（题材）。 */
@Composable
fun TagChip(text: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Text(
        text = text,
        color = Ink.TextDim,
        fontSize = Txt.Tiny,
        modifier = modifier
            .clip(RoundedCornerShape(Dim.RadiusSm))
            .background(Ink.CardHi)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = Dim.Gap8, vertical = 3.dp),
    )
}

/**
 * 居中空态。
 *
 * 空态必须给出**下一步动作**（[hint]），只说"没有内容"用户会以为坏了。
 */
@Composable
fun EmptyState(
    text: String,
    hint: String = "",
    actionText: String = "",
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.layout.Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = text,
                color = Ink.TextDim,
                fontSize = Txt.Body,
                textAlign = TextAlign.Center,
            )
            if (hint.isNotBlank()) {
                Spacer(Modifier.height(Dim.Gap6))
                Text(
                    text = hint,
                    color = Ink.TextFaint,
                    fontSize = Txt.Caption,
                    textAlign = TextAlign.Center,
                )
            }
            if (actionText.isNotBlank() && onAction != null) {
                Spacer(Modifier.height(Dim.Gap16))
                PillButton(actionText, filled = true, onClick = onAction)
            }
        }
    }
}

/** 一条细分隔线。 */
@Composable
fun HairLine(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Ink.Line))
}
