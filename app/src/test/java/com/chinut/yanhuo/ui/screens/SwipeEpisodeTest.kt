package com.chinut.yanhuo.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 上下滑切集的判定逻辑测试。
 *
 * 为什么专门给这一个纯函数写测试：
 *
 *   · 手势**没法**在单元测试里模拟（那是 instrumented test 的活），
 *     但"位移多少算切集"和"到头了不切"是纯逻辑；
 *   · 这两条最容易写错，而且错了在真机上很难发现 ——
 *     "第 1 集还往下滑"、"最后一集还往上滑"看起来都只是"没反应"，
 *     实际是边界判断漏了，会让人以为手势失灵；
 *   · 阈值取屏高的 1/3，靠手点着试很难覆盖"刚好差一点"和"刚好够"这两种情况。
 */
class SwipeEpisodeTest {

    /** 2400px 屏 → 阈值 800。 */
    private val h = 2400f
    private val th = h / 3f

    // ==================== 中间位置：两个方向都能切 ====================

    @Test
    fun `上滑超过阈值切下一集`() {
        assertEquals(2, resolveSwipeTarget(dy = -th - 1, threshold = th, current = 1, total = 96))
    }

    @Test
    fun `下滑超过阈值切上一集`() {
        assertEquals(4, resolveSwipeTarget(dy = th + 1, threshold = th, current = 5, total = 96))
    }

    // ==================== 阈值边界 ====================

    @Test
    fun `刚好等于阈值也算切`() {
        assertEquals(2, resolveSwipeTarget(dy = -th, threshold = th, current = 1, total = 96))
        assertEquals(4, resolveSwipeTarget(dy = th, threshold = th, current = 5, total = 96))
    }

    @Test
    fun `差一点不到阈值不切`() {
        assertEquals(0, resolveSwipeTarget(dy = -th + 1, threshold = th, current = 1, total = 96))
        assertEquals(0, resolveSwipeTarget(dy = th - 1, threshold = th, current = 5, total = 96))
    }

    @Test
    fun `没有位移不切`() {
        assertEquals(0, resolveSwipeTarget(dy = 0f, threshold = th, current = 5, total = 96))
    }

    // ==================== 边界：这一头最容易写错 ====================

    @Test
    fun `第 1 集继续下滑不切（没有上一集）`() {
        // 下滑方向 = 上一集，但已经在第 1 集 → 必须什么都不做，
        // 而不是切到"第 0 集"或者绕到最后一集
        assertEquals(0, resolveSwipeTarget(dy = th * 3, threshold = th, current = 1, total = 96))
    }

    @Test
    fun `最后一集继续上滑不切`() {
        // 短剧动辄上百集，用户很容易划到底；到底后不能循环回第 1 集
        assertEquals(0, resolveSwipeTarget(dy = -th * 3, threshold = th, current = 96, total = 96))
    }

    @Test
    fun `最后一集可以倒着滑回去`() {
        assertEquals(95, resolveSwipeTarget(dy = th, threshold = th, current = 96, total = 96))
    }

    @Test
    fun `第 1 集可以往上滑到第 2 集`() {
        assertEquals(2, resolveSwipeTarget(dy = -th, threshold = th, current = 1, total = 96))
    }

    // ==================== 只有一集 ====================

    @Test
    fun `只有一集时两个方向都不切`() {
        assertEquals(0, resolveSwipeTarget(dy = -th, threshold = th, current = 1, total = 1))
        assertEquals(0, resolveSwipeTarget(dy = th, threshold = th, current = 1, total = 1))
    }

    // ==================== 异常输入不应该崩 ====================

    @Test
    fun `阈值为零或负数时不切（防止除零式的误判）`() {
        assertEquals(0, resolveSwipeTarget(dy = -100f, threshold = 0f, current = 5, total = 96))
        assertEquals(0, resolveSwipeTarget(dy = -100f, threshold = -10f, current = 5, total = 96))
    }

    @Test
    fun `总集数为零时不切`() {
        assertEquals(0, resolveSwipeTarget(dy = -th, threshold = th, current = 1, total = 0))
    }

    @Test
    fun `当前集号越界时不越界返回`() {
        // 防御性：current 已经超过 total 时，上滑不能返回 total+1 之外的数
        assertEquals(0, resolveSwipeTarget(dy = -th, threshold = th, current = 96, total = 96))
    }
}
