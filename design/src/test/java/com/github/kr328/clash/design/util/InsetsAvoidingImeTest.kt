package com.github.kr328.clash.design.util

import com.github.kr328.clash.design.ui.Insets
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 回归 QA P2-A：底部弹层（AppBottomSheetDialog）在软键盘弹起时必须让出键盘高度，
 * 否则表单底部的确认按钮被键盘盖住且滚动不到。
 *
 * 弹层窗口设了 `decorFitsSystemWindows = false`，窗口不随 IME 收缩，因此「该让出多少」
 * 完全由本函数合成的底部 inset 决定——这就是该行为唯一的可测决策点。
 */
class InsetsAvoidingImeTest {
    private val navigationBar = 48

    private val base = Insets(start = 0, top = 72, end = 0, bottom = navigationBar)

    @Test
    fun `keyboard hidden keeps system bar inset`() {
        assertEquals(base, base.avoidingIme(imeBottom = 0))
    }

    @Test
    fun `keyboard shown yields the keyboard height`() {
        val keyboard = 840

        assertEquals(keyboard, base.avoidingIme(imeBottom = keyboard).bottom)
    }

    @Test
    fun `keyboard height is not stacked on top of the navigation bar`() {
        val keyboard = 840

        // IME inset 本身已含被键盘覆盖的导航栏区域；两者相加会在键盘之上再留一条空白。
        assertEquals(keyboard, base.avoidingIme(imeBottom = keyboard).bottom)
    }

    @Test
    fun `ime smaller than the system bar falls back to the system bar`() {
        assertEquals(navigationBar, base.avoidingIme(imeBottom = 12).bottom)
    }

    @Test
    fun `horizontal and top insets are never altered`() {
        val insets = Insets(start = 20, top = 72, end = 30, bottom = navigationBar)
        val adjusted = insets.avoidingIme(imeBottom = 840)

        assertEquals(20, adjusted.start)
        assertEquals(72, adjusted.top)
        assertEquals(30, adjusted.end)
    }
}
