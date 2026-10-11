package com.chinut.yanhuo.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * 主题。
 *
 * 短剧 App 的封面都是高饱和的竖图，**只有深色底能让封面跳出来**
 * （浅色底会把视线从内容抢走）。所以这里不跟随系统深浅色，
 * 恒定用深色 —— 这也是同类产品的通行做法。
 */
private val scheme = darkColorScheme(
    primary = Ink.Brand,
    onPrimary = Ink.Deep,
    primaryContainer = Ink.BrandGlow,
    onPrimaryContainer = Ink.Deep,
    secondary = Ink.Accent,
    onSecondary = Ink.Deep,
    tertiary = Ink.Magenta,
    onTertiary = Ink.Deep,
    background = Ink.Deep,
    onBackground = Ink.Text,
    surface = Ink.Base,
    onSurface = Ink.Text,
    surfaceVariant = Ink.Card,
    onSurfaceVariant = Ink.TextDim,
    outline = Ink.Line,
    error = Ink.Warn,
)

@Composable
fun YanhuoTheme(content: @Composable () -> Unit) {
    @Suppress("UNUSED_EXPRESSION")
    isSystemInDarkTheme() // 目前恒深色；留这行是为了将来做浅色时有个明确入口
    MaterialTheme(colorScheme = scheme, content = content)
}
