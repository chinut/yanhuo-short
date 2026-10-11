package com.chinut.yanhuo.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 设计令牌。
 *
 * 色板直接取自开屏图的霓虹配色（橙焰 + 青蓝 + 品红紫），
 * 这样 App 内部和图标/开屏是一套视觉，不会出现"图标很亮、界面很灰"。
 *
 * 命名用语义（`Bg` / `Card` / `Text`）而不是颜色名（`DarkBlue`），
 * 以后调整色板时不用改调用点。
 */
object Ink {
    /** 页面底色（最暗）。 */
    val Deep = Color(0xFF05060E)

    /** 页面底色（渐变中段）。 */
    val Base = Color(0xFF0B0D1A)

    /** 卡片底色。 */
    val Card = Color(0xFF141830)

    /** 卡片抬起态（按下 / 选中）。 */
    val CardHi = Color(0xFF1C2240)

    /** 分割线。 */
    val Line = Color(0xFF232845)

    /** 主色：焰火橙。 */
    val Brand = Color(0xFFFF6B2C)

    /** 主色亮部（渐变用）。 */
    val BrandGlow = Color(0xFFFFA45C)

    /** 次色：青蓝。 */
    val Accent = Color(0xFF22D3EE)

    /** 强调色：品红（订阅更新提示用）。 */
    val Magenta = Color(0xFFE879F9)

    /** 文字：主。 */
    val Text = Color(0xFFF2F4FF)

    /** 文字：次。 */
    val TextDim = Color(0xFFA7AECB)

    /** 文字：弱（说明文字、时间戳）。 */
    val TextFaint = Color(0xFF6B7391)

    /** 危险/警告。 */
    val Warn = Color(0xFFFFB020)

    /** 面板遮罩。 */
    val Scrim = Color(0xCC000000)
}
